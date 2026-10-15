// 智農派工 後端 API 效能測試工具（暫存腳本，不屬於專案）
// 用法：
//   node perf.mjs probe                      逐一探測端點（狀態碼、大小、單次延遲）
//   node perf.mjs load <scenario> <conc> <sec>   單一端點固定並發壓測
//   node perf.mjs ramp                       全部端點 × 並發階梯
import http from 'node:http';
import fs from 'node:fs';

const HOST = process.env.HOST || 'localhost';
const PORT = +(process.env.PORT || 3000);
const OUT = process.env.OUT || '.';

function makeAgent(n) { return new http.Agent({ keepAlive: true, maxSockets: n, maxFreeSockets: n }); }

function request(agent, { method = 'GET', path, token, body, gzip = false }) {
  return new Promise((resolve) => {
    const t0 = process.hrtime.bigint();
    const data = body ? Buffer.from(JSON.stringify(body)) : null;
    const headers = {};
    if (token) headers.Authorization = 'Bearer ' + token;
    if (data) { headers['Content-Type'] = 'application/json'; headers['Content-Length'] = data.length; }
    if (gzip) headers['Accept-Encoding'] = 'gzip';
    const req = http.request({ host: HOST, port: PORT, method, path, headers, agent }, (res) => {
      let bytes = 0;
      res.on('data', (c) => { bytes += c.length; });
      res.on('end', () => resolve({ status: res.statusCode, bytes, ms: Number(process.hrtime.bigint() - t0) / 1e6, enc: res.headers['content-encoding'] || '' }));
    });
    req.setTimeout(30000, () => { req.destroy(); resolve({ status: 0, bytes: 0, ms: 30000, enc: '' }); });
    req.on('error', () => resolve({ status: 0, bytes: 0, ms: Number(process.hrtime.bigint() - t0) / 1e6, enc: '' }));
    if (data) req.write(data);
    req.end();
  });
}

async function getJson(path, opts = {}) {
  const a = makeAgent(1);
  return new Promise((resolve, reject) => {
    const data = opts.body ? Buffer.from(JSON.stringify(opts.body)) : null;
    const headers = { 'Content-Type': 'application/json' };
    if (opts.token) headers.Authorization = 'Bearer ' + opts.token;
    const req = http.request({ host: HOST, port: PORT, method: opts.method || 'GET', path, headers, agent: a }, (res) => {
      const chunks = []; res.on('data', (c) => chunks.push(c));
      res.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (e) { resolve(null); } a.destroy(); });
    });
    req.on('error', reject); if (data) req.write(data); req.end();
  });
}

export async function tokens() {
  const t = {};
  for (const role of ['admin_tech', 'farmer', 'worker_local']) {
    const r = await getJson('/api/auth/demo-login', { method: 'POST', body: { role } });
    t[role] = r.token; t[role + ':ref'] = r.user.refId;
  }
  return t;
}

function pct(sorted, p) { if (!sorted.length) return 0; return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))]; }

export function summarize(samples, wallMs) {
  const lat = samples.map((s) => s.ms).sort((a, b) => a - b);
  const ok = samples.filter((s) => s.status >= 200 && s.status < 400).length;
  const bytes = samples.reduce((a, s) => a + s.bytes, 0);
  const statuses = {};
  samples.forEach((s) => { statuses[s.status] = (statuses[s.status] || 0) + 1; });
  return {
    n: samples.length, ok, err: samples.length - ok,
    rps: +(samples.length / (wallMs / 1000)).toFixed(1),
    p50: +pct(lat, 0.5).toFixed(1), p95: +pct(lat, 0.95).toFixed(1), p99: +pct(lat, 0.99).toFixed(1),
    max: +(lat[lat.length - 1] || 0).toFixed(1), avg: +(lat.reduce((a, b) => a + b, 0) / (lat.length || 1)).toFixed(1),
    avgBytes: Math.round(bytes / (samples.length || 1)), statuses,
  };
}

export async function runLoad(makeReq, conc, sec) {
  const agent = makeAgent(conc);
  const samples = [];
  const end = Date.now() + sec * 1000;
  const t0 = Date.now();
  async function vu(i) {
    let k = 0;
    while (Date.now() < end) { samples.push(await request(agent, makeReq(i, k++))); }
  }
  await Promise.all(Array.from({ length: conc }, (_, i) => vu(i)));
  agent.destroy();
  return summarize(samples, Date.now() - t0);
}

export { request, makeAgent, getJson };
