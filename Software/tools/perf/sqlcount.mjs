import fs from 'node:fs';
import { tokens, getJson } from './perf.mjs';
const LOG = process.env.LOG;
const t = await tokens(); const W = t['worker_local:ref'];
const count = () => (fs.readFileSync(LOG, 'utf8').match(/^Hibernate:/gm) || []).length;
const eps = [
  ['GET /api/crops', 'GET', '/api/crops'],
  ['POST /api/orders/calculate', 'POST', '/api/orders/calculate', t.farmer, { cropId: 'taoyuan_veg', area: 5, plan: 'early_bird', zone: 'core_35km', dispatchCategory: 'harvest_grading', workers: 5 }],
  ['GET /api/matching/orders/{id}', 'GET', '/api/matching/orders/ORD-2026-001', t.farmer],
  ['GET /api/skills/workers', 'GET', '/api/skills/workers', t.admin_tech],
  ['GET /api/insurance/workers', 'GET', '/api/insurance/workers', t.admin_tech],
  ['GET /api/orders (farmer)', 'GET', '/api/orders', t.farmer],
  ['GET /api/dispatch/overview', 'GET', '/api/dispatch/overview'],
  [`GET /api/workers/${W}/schedule`, 'GET', `/api/workers/${W}/schedule`, t.worker_local],
  [`GET /api/workers/${W}/payroll`, 'GET', `/api/workers/${W}/payroll`, t.worker_local],
  ['GET /api/workers (admin)', 'GET', '/api/workers', t.admin_tech],
  ['GET /api/auth/me', 'GET', '/api/auth/me', t.farmer],
];
const res = [];
for (const [n, m, p, tok, body] of eps) {
  await getJson(p, { method: m, token: tok, body });          // 暖機
  await new Promise((r) => setTimeout(r, 300));
  const b = count(); await getJson(p, { method: m, token: tok, body });
  await new Promise((r) => setTimeout(r, 300));
  const a = count(); res.push({ endpoint: n, sql: a - b }); console.log(String(a - b).padStart(5), 'SQL  ', n);
}
fs.writeFileSync(process.env.OUT + '/sqlcount.json', JSON.stringify(res, null, 2));
