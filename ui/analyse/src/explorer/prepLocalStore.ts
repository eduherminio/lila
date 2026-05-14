import { makeUci } from 'chessops';
import { makeFen } from 'chessops/fen';
import { parsePgn, startingPosition } from 'chessops/pgn';
import { makeSanAndPlay, parseSan } from 'chessops/san';

import { objectStorage, type ObjectStorage } from 'lib/objectStorage';

import type { OpeningData, OpeningMoveStats } from './interfaces';

const activeDatasetStorageKey = 'analyse.prepExplorer.datasetId';

interface StoredMoveStats {
  san: San;
  white: number;
  black: number;
  draws: number;
}

interface StoredPosition {
  white: number;
  black: number;
  draws: number;
  moves: Record<Uci, StoredMoveStats>;
}

interface StoredDataset {
  id: string;
  createdAt: number;
  games: number;
  players: string[];
  positions: Record<string, StoredPosition>;
}

let datasetDb: Promise<ObjectStorage<StoredDataset, string>> | undefined;
const db = () =>
  (datasetDb ??= objectStorage<StoredDataset, string>({
    store: 'prep-explorer-datasets',
    db: 'lichess',
  }));

const keyOf = (rootFen: FEN, play: string[]) => `${rootFen}\0${play.join(',')}`;

const resultKey = (result: string | undefined): 'white' | 'black' | 'draws' | undefined =>
  result === '1-0' ? 'white' : result === '0-1' ? 'black' : result === '1/2-1/2' ? 'draws' : undefined;

function incTotal(pos: StoredPosition, result: 'white' | 'black' | 'draws') {
  pos[result] += 1;
}

function upsertPosition(
  positions: Record<string, StoredPosition>,
  key: string,
  uci: Uci,
  san: San,
  result: 'white' | 'black' | 'draws',
) {
  const pos =
    positions[key] ??
    (positions[key] = {
      white: 0,
      black: 0,
      draws: 0,
      moves: {},
    });
  incTotal(pos, result);
  const move = pos.moves[uci] ?? (pos.moves[uci] = { san, white: 0, black: 0, draws: 0 });
  incTotal(move, result);
}

export function getActivePrepDatasetId(): string | undefined {
  return localStorage.getItem(activeDatasetStorageKey) || undefined;
}

export async function importPrepPgnDataset(datasetId: string, pgn: string) {
  const positions: Record<string, StoredPosition> = {};
  const players = new Set<string>();
  let games = 0;

  for (const game of parsePgn(pgn)) {
    const result = resultKey(game.headers.get('Result'));
    if (!result) continue;
    const white = game.headers.get('White');
    const black = game.headers.get('Black');
    if (white && white !== '?') players.add(white);
    if (black && black !== '?') players.add(black);
    const start = startingPosition(game.headers).unwrap();
    if (start.rules !== 'chess') continue;

    const startingFen: FEN = game.headers.get('FEN') || makeFen(start.toSetup());

    let tree = game.moves;
    const pos = start;
    const play: string[] = [];
    let addedAnyMove = false;

    while (tree.children.length) {
      const [mainline] = tree.children;
      const move = parseSan(pos, mainline.data.san);
      if (!move) break;
      const san = makeSanAndPlay(pos, move);
      const uci = makeUci(move);
      upsertPosition(positions, keyOf(startingFen, play), uci, san, result);
      play.push(uci);
      tree = mainline;
      addedAnyMove = true;
    }

    if (addedAnyMove) games += 1;
  }

  const dataset: StoredDataset = {
    id: datasetId,
    createdAt: Date.now(),
    games,
    players: [...players],
    positions,
  };
  await (await db()).put(datasetId, dataset);
  localStorage.setItem(activeDatasetStorageKey, datasetId);
  return { id: dataset.id, games: dataset.games, players: dataset.players };
}

export async function queryPrepOpening(opts: {
  datasetId?: string;
  rootFen: FEN;
  play: string[];
  fen: FEN;
}): Promise<OpeningData> {
  const datasetId = opts.datasetId || getActivePrepDatasetId();
  const empty: OpeningData = {
    fen: opts.fen,
    isOpening: true,
    white: 0,
    black: 0,
    draws: 0,
    moves: [],
  };
  if (!datasetId) return empty;
  const dataset = await (await db()).getOpt(datasetId);
  if (!dataset) return empty;
  const pos = dataset.positions[keyOf(opts.rootFen, opts.play)];
  if (!pos) return empty;
  const moves: OpeningMoveStats[] = Object.entries(pos.moves)
    .map(([uci, s]) => ({
      uci,
      san: s.san,
      white: s.white,
      black: s.black,
      draws: s.draws,
    }))
    .sort((a, b) => b.white + b.black + b.draws - (a.white + a.black + a.draws));
  return {
    fen: opts.fen,
    isOpening: true,
    white: pos.white,
    black: pos.black,
    draws: pos.draws,
    moves,
  };
}
