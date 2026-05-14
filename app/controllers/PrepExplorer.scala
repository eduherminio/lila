package controllers

import chess.Color
import chess.format.pgn.PgnStr
import play.api.libs.json.Json
import play.api.mvc.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import lila.app.{ *, given }
import lila.tree.ParseImport

final class PrepExplorer(env: Env) extends LilaController(env):

  import PrepExplorer.*

  private val uploadRateLimit =
    env.security.ipTrust.rateLimit(30, 10.minutes, "prepExplorer.upload", _.proxyMultiplier(3))
  private val queryRateLimit =
    env.security.ipTrust.rateLimit(600, 10.minutes, "prepExplorer.query", _.proxyMultiplier(3))

  def index = render(none)
  def show(dataset: String) = render(dataset.some)

  private def render(dataset: Option[String]) = Open:
    val pov = makePov(none, chess.variant.Standard)
    val orientation = get("color").flatMap(Color.fromName) | pov.color
    val prepCfg = Json.obj(
      "enabled" -> true,
      "datasetId" -> dataset,
      "uploadEndpoint" -> routes.PrepExplorer.upload.url,
      "datasetPageBase" -> routes.PrepExplorer.index.url
    )
    for
      data <- env.api.roundApi.userAnalysisJson(
        pov,
        ctx.pref,
        none,
        orientation,
        owner = false
      )
      page <- renderPage(views.analyse.ui.userAnalysis(data, pov, prepExplorer = prepCfg.some))
    yield Ok(page).enforceCrossSiteIsolation

  private def makePov(fen: Option[chess.format.Fen.Full], variant: chess.variant.Variant): Pov =
    makePov:
      chess.Position.AndFullMoveNumber(variant, fen.filter(_.value.nonEmpty))

  private def makePov(from: chess.Position.AndFullMoveNumber): Pov =
    Pov(
      lila.core.game
        .newGame(
          chess = chess.Game(position = from.position, ply = from.ply),
          players = chess.ByColor(lila.game.Player.make(_, none)),
          rated = chess.Rated.No,
          source = lila.core.game.Source.Api,
          pgnImport = None
        )
        .withId(lila.game.Game.syntheticId),
      from.position.color
    )

  def upload = OpenBody(parse.multipartFormData): ctx ?=>
    uploadRateLimit(rateLimited):
      val body = ctx.body.body
      val rawPgn = body.dataParts.get("pgn").flatMap(_.headOption).map(_.trim).filter(_.nonEmpty).orElse:
        body.file("file").flatMap: file =>
          scala.util.Try(Files.readString(file.ref.path, StandardCharsets.UTF_8)).toOption
      rawPgn.fold(BadRequest(jsonError("Missing PGN input")).toFuccess): pgn =>
        PrepExplorerStore.create(pgn) match
          case Left(err) => BadRequest(jsonError(err)).toFuccess
          case Right(result) =>
            JsonOk(
              Json.obj(
                "id" -> result.id,
                "games" -> result.gameCount,
                "errors" -> result.errors.take(30),
                "players" -> result.players.take(30)
              )
            ).toFuccess

  def player(dataset: String) = Open:
    queryRateLimit(rateLimited):
      val prefix = get("play").fold(Vector.empty[String])(_.split(',').toVector.filter(_.nonEmpty))
      val fen = get("fen") | chess.variant.Standard.initialFen.value
      val player = get("player").map(_.trim).filter(_.nonEmpty)
      val color = get("color").flatMap(Color.fromName) | chess.White
      PrepExplorerStore
        .query(dataset, prefix, player, color)
        .fold(NotFound(jsonError("Dataset not found")).toFuccess): data =>
          val payload = Json.stringify(data.add("fen", fen)) + "\n"
          fuccess(Ok(payload).as("application/x-ndjson"))

object PrepExplorer:

  private val maxPgnBytes = 3_000_000
  private val maxGames = 5_000
  // Anonymous uploaded datasets are ephemeral and automatically cleaned up.
  private val datasetTtl = 24.hours

  private case class MoveStep(uci: String, san: String)

  private case class DatasetGame(
      white: String,
      black: String,
      winner: Option[Color],
      moves: Vector[MoveStep]
  ):
    def colorName(color: Color) = if color.white then white else black

  private case class Dataset(
      id: String,
      createdAt: Instant,
      expiresAt: Instant,
      games: Vector[DatasetGame],
      players: Vector[String],
      errors: Vector[String]
  )

  case class CreateResult(id: String, gameCount: Int, errors: Vector[String], players: Vector[String])

  private object PrepExplorerStore:
    // Intentionally in-memory for MVP simplicity; data is lost on restart.
    private val datasets = TrieMap.empty[String, Dataset]

    def create(rawPgn: String): Either[String, CreateResult] =
      val bytes = rawPgn.getBytes(StandardCharsets.UTF_8).length
      if bytes > maxPgnBytes then Left(s"PGN payload too large (max ${maxPgnBytes / 1000}KB)")
      else
        val chunks = splitGames(rawPgn).take(maxGames)
        if chunks.isEmpty then Left("No games found in PGN")
        else
          val errors = mutable.ArrayBuffer.empty[String]
          val games = chunks.zipWithIndex.flatMap:
            case (chunk, i) =>
            ParseImport.full(PgnStr(chunk)) match
              case Left(err) =>
                errors += s"Game ${i + 1}: ${err.value}"
                none
              case Right(parsed) =>
                val tags = parsed.parsed.tags
                val white = tags("White") | "Unknown White"
                val black = tags("Black") | "Unknown Black"
                val moves = parsed.replay.chronoMoves.map(m => MoveStep(m.toUci.uci, m.toSanStr.value)).toVector
                Option.when(moves.nonEmpty):
                  DatasetGame(
                    white = white,
                    black = black,
                    winner = parsed.result.flatMap(_.winner),
                    moves = moves
                  )

          if games.isEmpty then Left("No valid games could be imported from PGN")
          else
            val now = Instant.now
            val id = randomId()
            val players = games
              .flatMap(g => List(g.white, g.black))
              .groupBy(_.toLowerCase)
              .toVector
              .sortBy { case (_, names) => -names.size }
              .flatMap(_._2.headOption)
            val dataset = Dataset(
              id = id,
              createdAt = now,
              expiresAt = now.plusSeconds(datasetTtl.toSeconds),
              games = games.toVector,
              players = players,
              errors = errors.toVector
            )
            purgeExpired(now)
            datasets.put(id, dataset)
            Right(CreateResult(id, dataset.games.size, dataset.errors, dataset.players))

    def query(
        id: String,
        prefix: Vector[String],
        player: Option[String],
        color: Color
    ): Option[play.api.libs.json.JsObject] =
      val now = Instant.now
      purgeExpired(now)
      datasets.get(id).map: ds =>
        val playerLower = player.map(_.toLowerCase)
        val filtered = ds.games.filter: g =>
          playerLower.forall(p => g.colorName(color).toLowerCase == p)
        val inPosition = filtered.filter(g => hasPrefix(g.moves, prefix))

        val moveAcc = mutable.Map.empty[String, (Int, Int, Int)]
        var white = 0
        var draws = 0
        var black = 0

        inPosition.foreach: g =>
          g.winner match
            case Some(chess.White) => white += 1
            case Some(chess.Black) => black += 1
            case _ => draws += 1
          g.moves.lift(prefix.size).foreach: step =>
            val (w, d, b) = moveAcc.getOrElse(step.uci, (0, 0, 0))
            val updated = g.winner match
              case Some(chess.White) => (w + 1, d, b)
              case Some(chess.Black) => (w, d, b + 1)
              case _ => (w, d + 1, b)
            moveAcc.update(step.uci, updated)

        val sanByUci = inPosition
          .flatMap(_.moves.lift(prefix.size))
          .map(step => step.uci -> step.san)
          .toMap

        val moves = moveAcc.toVector
          .sortBy: (_, (w, d, b)) =>
            -(w + d + b)
          .map: (uci, (w, d, b)) =>
              Json.obj(
                "uci" -> uci,
                "san" -> sanByUci.getOrElse(uci, uci),
                "white" -> w,
                "draws" -> d,
                "black" -> b
            )

        Json.obj(
          "white" -> white,
          "draws" -> draws,
          "black" -> black,
          "moves" -> moves,
          "topGames" -> Json.arr(),
          "recentGames" -> Json.arr()
        )

    private def hasPrefix(moves: Vector[MoveStep], prefix: Vector[String]) =
      prefix.size <= moves.size && prefix.indices.forall(i => moves(i).uci == prefix(i))

    private def splitGames(rawPgn: String): Vector[String] =
      val normalized = rawPgn.replace("\r\n", "\n").trim
      if normalized.isEmpty then Vector.empty
      else
        val chunks = "(?m)(?=^\\[Event\\s+\")".r.split(normalized).toVector.map(_.trim).filter(_.nonEmpty)
        if chunks.nonEmpty then chunks else Vector(normalized)

    private def purgeExpired(now: Instant): Unit =
      datasets.filterInPlace((_, ds) => !ds.expiresAt.isBefore(now))

    private def randomId(): String =
      val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
      (1 to 12)
        .map(_ => alphabet(ThreadLocalRandom.current().nextInt(alphabet.length)))
        .mkString
