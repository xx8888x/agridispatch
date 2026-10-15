import fs from 'node:fs';
import { tokens, runLoad, request, makeAgent, getJson, summarize } from './perf.mjs';

const OUT = process.env.OUT;
const t = await tokens();
const W = t['worker_local:ref'];
const OID = 'ORD-2026-001';
const results = { startedAt: new Date().toISOString(), phases: [] };
const log = (s) => console.log(s);
function save() { fs.writeFileSync(OUT + '/load.json', JSON.stringify(results, null, 2)); }
async function phase(name, fn) {
  const t0 = new Date().toISOString();
  const r = await fn();
  results.phases.push({ name, t0, t1: new Date().toISOString(), ...r });
  save();
  return r;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const calcBody = { cropId: 'taoyuan_veg', area: 5, plan: 'early_bird', zone: 'core_35km', dispatchCategory: 'harvest_grading', workers: 5 };

// ---------- A. 各端點：單人基準 ＋ 50 並發 ----------
const EP = {
  'GET /api/public/config':          () => ({ path: '/api/public/config' }),
  'GET /api/crops':                  () => ({ path: '/api/crops' }),
  'GET /api/calendar':               () => ({ path: '/api/calendar' }),
  'GET /api/brochures':              () => ({ path: '/api/brochures' }),
  'GET /api/dispatch/overview':      () => ({ path: '/api/dispatch/overview' }),
  'POST /api/orders/calculate':      () => ({ method: 'POST', path: '/api/orders/calculate', body: calcBody }),
  'GET /api/auth/me':                () => ({ path: '/api/auth/me', token: t.farmer }),
  'GET /api/orders (farmer)':        () => ({ path: '/api/orders', token: t.farmer }),
  'GET /api/orders/{id}':            () => ({ path: '/api/orders/' + OID, token: t.farmer }),
  'GET /api/rosters':                () => ({ path: '/api/rosters', token: t.farmer }),
  'GET /api/dispatch/teams':         () => ({ path: '/api/dispatch/teams', token: t.farmer }),
  'GET /api/workers/{id}/schedule':  () => ({ path: `/api/workers/${W}/schedule`, token: t.worker_local }),
  'GET /api/workers/{id}/payroll':   () => ({ path: `/api/workers/${W}/payroll`, token: t.worker_local }),
  'GET /api/workers (admin)':        () => ({ path: '/api/workers', token: t.admin_tech }),
  'GET /api/insurance/workers':      () => ({ path: '/api/insurance/workers', token: t.admin_tech }),
  'GET /api/auth/accounts':          () => ({ path: '/api/auth/accounts', token: t.admin_tech }),
  'GET /api/matching/orders/{id}':   () => ({ path: '/api/matching/orders/' + OID, token: t.farmer }),
  'GET /api/skills/workers':         () => ({ path: '/api/skills/workers', token: t.admin_tech }),
  'GET /api/weather/towns (cached)': () => ({ path: '/api/weather/towns', token: t.farmer }),
};
log('== A. 單端點');
await runLoad(() => EP['GET /api/crops'](), 8, 3); // 暖機
for (const [name, mk] of Object.entries(EP)) {
  const c1 = await runLoad(() => mk(), 1, 4);
  const c50 = await runLoad(() => mk(), 50, 8);
  await phase('A:' + name, async () => ({ kind: 'endpoint', name, c1, c50 }));
  log(`${name.padEnd(34)} c1 p50 ${c1.p50}ms p95 ${c1.p95}ms | c50 ${c50.rps} rps p50 ${c50.p50} p95 ${c50.p95} p99 ${c50.p99} err ${c50.err}`);
}

// ---------- B. 混合流量階梯 ----------
const MIX = [
  [4, EP['GET /api/crops']], [3, EP['GET /api/calendar']], [3, EP['GET /api/dispatch/overview']], [2, EP['GET /api/brochures']],
  [6, EP['GET /api/auth/me']], [6, EP['GET /api/orders (farmer)']], [4, EP['GET /api/rosters']], [3, EP['GET /api/dispatch/teams']], [2, EP['GET /api/orders/{id}']],
  [7, EP['GET /api/workers/{id}/schedule']], [5, EP['GET /api/workers/{id}/payroll']],
  [4, EP['GET /api/workers (admin)']], [2, EP['GET /api/insurance/workers']], [2, EP['GET /api/auth/accounts']],
  [4, EP['POST /api/orders/calculate']], [1, EP['GET /api/matching/orders/{id}']],
];
const total = MIX.reduce((a, [w]) => a + w, 0);
function pick(i, k) { let r = ((i * 7919 + k * 104729) % total + total) % total; for (const [w, f] of MIX) { if ((r -= w) < 0) return f(); } return MIX[0][1](); }
log('== B. 混合流量階梯');
const ramp = [];
for (const c of [10, 25, 50, 100, 200, 400]) {
  const r = await runLoad((i, k) => pick(i, k), c, 15);
  ramp.push({ c, ...r });
  log(`mix c=${String(c).padEnd(3)} ${r.rps} rps  p50 ${r.p50} p95 ${r.p95} p99 ${r.p99} max ${r.max} err ${r.err}/${r.n}`);
  await sleep(1500);
}
await phase('B:mix-ramp', async () => ({ kind: 'ramp', ramp }));

// ---------- C. 重量端點階梯 ----------
log('== C. 重量端點');
const heavy = {};
for (const name of ['GET /api/skills/workers', 'GET /api/matching/orders/{id}', 'GET /api/insurance/workers', 'GET /api/weather/towns (cached)']) {
  heavy[name] = [];
  for (const c of [1, 5, 10, 25, 50]) {
    const r = await runLoad(() => EP[name](), c, 8);
    heavy[name].push({ c, ...r });
    log(`${name.padEnd(34)} c=${String(c).padEnd(3)} ${r.rps} rps p50 ${r.p50} p95 ${r.p95} max ${r.max} err ${r.err}`);
  }
}
await phase('C:heavy', async () => ({ kind: 'heavy', heavy }));

// ---------- D. 登入（PBKDF2）----------
log('== D. 登入');
const loginRes = [];
for (const c of [1, 4, 12, 24, 48]) {
  const r = await runLoad(() => ({ method: 'POST', path: '/api/auth/login', body: { identifier: 'admin_tech', password: 'admin888' } }), c, 8);
  loginRes.push({ c, ...r });
  log(`login c=${String(c).padEnd(3)} ${r.rps} rps p50 ${r.p50} p95 ${r.p95} max ${r.max} err ${r.err}`);
}
// 同時混入登入時，其它 API 受影響程度
const baseline = await runLoad(() => EP['GET /api/orders (farmer)'](), 10, 6);
const during = await (async () => {
  const bg = runLoad(() => ({ method: 'POST', path: '/api/auth/login', body: { identifier: 'admin_tech', password: 'admin888' } }), 12, 10);
  await sleep(1500);
  const fg = await runLoad(() => EP['GET /api/orders (farmer)'](), 10, 6);
  await bg; return fg;
})();
log(`GET /api/orders c=10 : 單獨 p50 ${baseline.p50} p95 ${baseline.p95} | 登入洪泛中 p50 ${during.p50} p95 ${during.p95} max ${during.max}`);
await phase('D:login', async () => ({ kind: 'login', loginRes, interference: { baseline, during } }));

// ---------- F. 靜態檔（後端同時提供 /demo、/web）----------
log('== F. 靜態檔');
const statics = {};
for (const p of ['/demo/AgriDispatch/index.html', '/demo/AgriDispatch/farmer.html', '/demo/AgriDispatch/js/vendor/vue.global.prod.js', '/demo/AgriDispatch/js/vendor/tailwindcss-3.4.17.js', '/demo/mock/demo-fixtures.js', '/web/AgriDispatch/farmer.html']) {
  statics[p] = [];
  for (const c of [1, 20, 100]) {
    const r = await runLoad(() => ({ path: p }), c, 6);
    statics[p].push({ c, ...r });
    log(`${p.padEnd(52)} c=${String(c).padEnd(3)} ${r.rps} rps p50 ${r.p50} p95 ${r.p95} err ${r.err} ${(r.avgBytes / 1024).toFixed(0)}KB  ≈${(r.rps * r.avgBytes / 1048576).toFixed(0)}MB/s`);
  }
}
await phase('F:static', async () => ({ kind: 'static', statics }));

// ---------- G. 浸泡 ----------
log('== G. 浸泡 60s @ 100');
const soak = [];
const t0s = Date.now();
while (Date.now() - t0s < 60000) {
  const r = await runLoad((i, k) => pick(i, k), 100, 10);
  soak.push(r);
  log(`soak ${Math.round((Date.now() - t0s) / 1000)}s ${r.rps} rps p50 ${r.p50} p95 ${r.p95} p99 ${r.p99} err ${r.err}`);
}
await phase('G:soak', async () => ({ kind: 'soak', soak }));
// ---------- E. 寫入與資料量成長 ----------
log('== E. 寫入與資料量成長');
const orderBody = (i) => ({ farmerName: '效能測試農場', farmLocation: '桃園市八德區測試路' + i + '號', phone: '0912-345-678', cropId: 'taoyuan_veg', area: 2, plan: 'early_bird',
  dispatchCategory: 'harvest_grading', transportZone: 'core_35km', startDate: '2027-03-0' + (1 + (i % 9)), endDate: '2027-03-1' + (i % 9), requestedWorkers: 3, timeSlot: 'full_day' });
const grow = [];
async function readTimes() {
  const a = await runLoad(() => EP['GET /api/orders (farmer)'](), 5, 4);
  const ad = await runLoad(() => ({ path: '/api/orders', token: t.admin_tech }), 5, 4);
  const sz = (await request(makeAgent(1), { path: '/api/orders', token: t.farmer })).bytes;
  return { farmer: a, admin: ad, farmerBytes: sz };
}
const cnt = async () => (await getJson('/api/orders', { token: t.admin_tech }))?.data?.length;
const s0 = { orders: await cnt(), ...(await readTimes()) };
grow.push(s0);
log(`orders=${s0.orders} farmer list p50 ${s0.farmer.p50} ms (${s0.farmerBytes}B) admin list p50 ${s0.admin.p50} ms`);
let created = 0; let failed = 0; const wr = [];
for (const batch of [100, 400, 1000]) {
  const agent = makeAgent(8);
  const t0 = Date.now(); const samples = [];
  async function vu(base) { for (let i = 0; i < batch / 8 && created < 5000; i++) { const s = await request(agent, { method: 'POST', path: '/api/orders', token: t.farmer, body: orderBody(base * 1000 + i) }); samples.push(s); if (s.status === 201) created++; else failed++; } }
  await Promise.all(Array.from({ length: 8 }, (_, j) => vu(j + created % 7)));
  agent.destroy();
  const sm = summarize(samples, Date.now() - t0); wr.push({ batch, ...sm });
  const snap = { orders: await cnt(), ...(await readTimes()) }; grow.push(snap);
  log(`+${batch} POST: ${sm.rps} rps p50 ${sm.p50} p95 ${sm.p95} err ${sm.err} | orders=${snap.orders} farmer list p50 ${snap.farmer.p50} p95 ${snap.farmer.p95} (${snap.farmerBytes}B) admin p50 ${snap.admin.p50}`);
}
await phase('E:write-growth', async () => ({ kind: 'growth', writes: wr, grow, created, failed }));

results.finishedAt = new Date().toISOString(); save();
log('DONE');
