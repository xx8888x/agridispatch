import { tokens, getJson, request, makeAgent } from './perf.mjs';
const t = await tokens();
const t0 = Date.now();
const r = await getJson('/api/dev/seed?cleanFirst=true', { method: 'POST', token: t.admin_tech });
console.log('seed', JSON.stringify(r), (Date.now() - t0) + 'ms');
const o = await getJson('/api/orders', { token: t.admin_tech });
console.log('orders now', o.data.length);
