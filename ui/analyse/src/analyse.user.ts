import { wsConnect } from 'lib/socket';

import makeBoot from './boot';
import makeStart from './start';
import { patch } from './view/util';

export { patch };

const start = makeStart(patch);
const boot = makeBoot(start);

export async function initModule({ mode, cfg }: { mode: 'userAnalysis' | 'replay' | 'prepExplorer'; cfg: any }) {
  await site.asset.loadPieces;
  if (mode === 'replay') boot(cfg);
  else userAnalysis(cfg);
}

function userAnalysis(cfg: any) {
  if (cfg.prepExplorer) {
    cfg.explorer.prep = cfg.prepExplorer;
    cfg.explorer.endpoint = `/api/prep-explorer/${cfg.prepExplorer.datasetId || 'no-dataset'}/`;
  }
  cfg.$side = $('.analyse__side').clone();
  cfg.socketSend = wsConnect(cfg.socketUrl || '/analysis/socket/v5', cfg.socketVersion, {
    receive: (t: string, d: any) => analyse.socketReceive(t, d),
  }).send;
  const analyse = start(cfg);
}
