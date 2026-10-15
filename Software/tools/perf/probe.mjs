import fs from 'node:fs';
import { tokens, request, makeAgent, getJson } from './perf.mjs';

const t = await tokens();
const orders = await getJson('/api/orders', { token: t.farmer });
const list = orders?.data || orders?.orders || orders;
console.log('orders shape:', Array.isArray(list) ? 'array len ' + list.length : Object.keys(orders || {}));
const oid = Array.isArray(list) && list.length ? (list[0].id || list[0].orderId) : null;
console.log('sample order id:', oid, 'farmer ref:', t['farmer:ref'], 'worker ref:', t['worker_local:ref']);

const W = t['worker_local:ref'];
const ep = [
  ['public', 'GET', '/api/public/config'],
  ['public', 'GET', '/api/crops'],
  ['public', 'GET', '/api/calendar'],
  ['public', 'GET', '/api/calendar/harvests'],
  ['public', 'GET', '/api/brochures'],
  ['public', 'GET', '/api/orders/categories'],
  ['public', 'GET', '/api/payments/methods'],
  ['public', 'GET', '/api/dispatch/overview'],
  ['public', 'GET', '/api/dispatch/weather-state'],
  ['public', 'POST', '/api/orders/calculate', null, { cropId: 1, days: 3, workers: 5 }],
  ['farmer', 'GET', '/api/auth/me', t.farmer],
  ['farmer', 'GET', '/api/orders', t.farmer],
  ['farmer', 'GET', oid ? `/api/orders/${oid}` : '/api/orders/x', t.farmer],
  ['farmer', 'GET', oid ? `/api/matching/orders/${oid}` : '/api/matching/orders/x', t.farmer],
  ['farmer', 'GET', '/api/rosters', t.farmer],
  ['farmer', 'GET', '/api/farmers/me/farms', t.farmer],
  ['farmer', 'GET', '/api/farmers/me/past-workers', t.farmer],
  ['farmer', 'GET', '/api/ratings/teams', t.farmer],
  ['farmer', 'GET', '/api/dispatch/teams', t.farmer],
  ['farmer', 'GET', '/api/notifications', t.farmer],
  ['farmer', 'GET', '/api/insurance/types', t.farmer],
  ['farmer', 'GET', '/api/weather/towns', t.farmer],
  ['worker', 'GET', `/api/workers/${W}`, t.worker_local],
  ['worker', 'GET', `/api/workers/${W}/schedule`, t.worker_local],
  ['worker', 'GET', `/api/workers/${W}/attendance`, t.worker_local],
  ['worker', 'GET', `/api/workers/${W}/payroll`, t.worker_local],
  ['worker', 'GET', `/api/workers/${W}/team-members`, t.worker_local],
  ['worker', 'GET', `/api/workers/${W}/leaves`, t.worker_local],
  ['worker', 'GET', `/api/attendance/today/${W}`, t.worker_local],
  ['worker', 'GET', '/api/leaves/types', t.worker_local],
  ['admin', 'GET', '/api/workers', t.admin_tech],
  ['admin', 'GET', '/api/leaves', t.admin_tech],
  ['admin', 'GET', '/api/payroll/months', t.admin_tech],
  ['admin', 'GET', '/api/payroll/attendance', t.admin_tech],
  ['admin', 'GET', '/api/insurance/workers', t.admin_tech],
  ['admin', 'GET', '/api/skills/workers', t.admin_tech],
  ['admin', 'GET', '/api/auth/accounts', t.admin_tech],
  ['admin', 'GET', '/api/talents', t.admin_tech],
  ['admin', 'GET', '/api/legalize/summary', t.admin_tech],
  ['admin', 'GET', '/api/line/bindings', t.admin_tech],
  ['admin', 'GET', '/api/orders', t.admin_tech],
  ['admin', 'GET', '/api/attendance/summary/present-count', t.admin_tech],
];

const agent = makeAgent(1);
const rows = [];
for (const [group, method, path, token, body] of ep) {
  // 第 1 次（冷）與其後 5 次取中位數
  const cold = await request(agent, { method, path, token, body });
  const warm = [];
  for (let i = 0; i < 8; i++) warm.push((await request(agent, { method, path, token, body })).ms);
  warm.sort((a, b) => a - b);
  rows.push({ group, method, path, status: cold.status, bytes: cold.bytes, cold: +cold.ms.toFixed(1), warmMed: +warm[4].toFixed(1), warmMax: +warm[7].toFixed(1) });
}
fs.writeFileSync(process.env.OUT + '/probe.json', JSON.stringify({ oid, rows }, null, 2));
for (const r of rows) console.log(`${r.group.padEnd(7)} ${r.method.padEnd(4)} ${r.path.padEnd(48)} ${String(r.status).padEnd(4)} ${String(r.bytes).padStart(8)}B  cold ${String(r.cold).padStart(7)}ms  warm ${String(r.warmMed).padStart(6)}ms (max ${r.warmMax})`);
