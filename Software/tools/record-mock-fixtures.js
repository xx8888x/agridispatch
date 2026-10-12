#!/usr/bin/env node
/**
 * 智農派工 (AgriDispatch) - 錄製純前端 Mock 資料
 *
 * 後端以 demo 模式在 3000 執行時，把各 API 的真實回應存成 demo/mock/demo-fixtures.js，
 * demo 前端（AGRI_CONFIG.mock = true）就能不靠後端展示。
 *
 * 來源：
 *   1. 頁面實際送出的請求清單（選用）：瀏覽器 localStorage.agri_mock = 'record' 後逐頁瀏覽，
 *      把 top.__agriRec 轉成 [[method, path, body, role], ...] 存成 JSON，以 --requests <檔案> 傳入
 *      （role 取自請求 Authorization token 的 payload）。
 *   2. 本腳本依列表 API 列舉的明細 API（每位工人、每筆訂單、每個工班…）。
 *
 * 用法（於 Software/）：
 *   node tools/record-mock-fixtures.js [--requests rec.json] [--base http://localhost:3000/api]
 */
'use strict';

const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def; };
const BASE = opt('--base', 'http://localhost:3000/api');
const REQ_FILE = opt('--requests', null);
const OUT = path.resolve(__dirname, '..', 'demo', 'mock', 'demo-fixtures.js');

const fixtures = {};
const tokens = {};

async function call(method, p, body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = token.startsWith('Bearer ') ? token : `Bearer ${token}`;
  const res = await fetch(BASE + p, { method, headers, body: body || undefined });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch (e) { json = text; }
  return { s: res.status, b: json };
}

async function login(role, refId) {
  const body = JSON.stringify(refId ? { role, refId } : { role });
  const r = await call('POST', '/auth/demo-login', body);
  fixtures['POST /auth/demo-login ' + body] = r;
  return r.b && r.b.token;
}

// 依序試各角色 token，取第一個不是 401/403 的回應
async function record(method, p, body, preferToken) {
  const key = method + ' ' + p + (body ? ' ' + body : '');
  if (fixtures[key] && fixtures[key].s < 400) return fixtures[key];
  const order = [preferToken, tokens.admin_tech, tokens.farmer, tokens.worker_local, tokens.worker_foreign].filter(Boolean);
  let r;
  for (const t of order) {
    r = await call(method, p, body, t);
    if (r.s !== 401 && r.s !== 403) break;
  }
  if (r && r.s !== 405) fixtures[key] = r;
  return r;
}

const data = r => (r && r.b && r.b.data) || [];
const enc = encodeURIComponent;

(async () => {
  for (const role of ['admin_tech', 'farmer', 'worker_local', 'worker_foreign']) tokens[role] = await login(role);

  // 示範帳號：各工人／農場主的 demo-login 與帳密登入
  const accountsFile = path.resolve(__dirname, '..', 'demo', 'AgriDispatch', 'js', 'demo-accounts.js');
  const sandbox = {};
  new Function('window', fs.readFileSync(accountsFile, 'utf8'))(sandbox);
  const A = sandbox.AGRI_DEMO_ACCOUNTS;
  for (const p of A.workerForeign.profiles) await login('worker_foreign', p.workerId);
  for (const p of A.workerLocal.profiles) await login('worker_local', p.workerId);
  const logins = [].concat(
    A.admin.profiles.map(p => ({ identifier: p.email, password: p.password })),
    A.farmer.profiles.map(p => ({ identifier: p.identifier, password: p.password })),
    A.workerForeign.profiles.map(p => ({ identifier: p.identifier, password: p.password })),
    A.workerLocal.profiles.map(p => ({ identifier: p.identifier, password: p.password }))
  );
  for (const l of logins) await record('POST', '/auth/login', JSON.stringify(l));

  // 天候情境：每個情境各錄一份調度快照（Mock 切換情境時套用），最後切回晴天作為預設狀態
  const SCENARIOS = ['cloudy', 'shower', 'heavy_rain', 'typhoon', 'cold_wave', 'sunny'];
  for (const id of SCENARIOS) {
    const body = JSON.stringify({ scenarioId: id });
    fixtures['POST /dispatch/weather-scenario ' + body] = await call('POST', '/dispatch/weather-scenario', body, tokens.admin_tech);
    const teamList = await call('GET', '/dispatch/teams', null, tokens.admin_tech);
    fixtures[`SCENARIO ${id} GET /dispatch/teams`] = teamList;
    for (const p of ['/dispatch/overview', '/dispatch/weather-state'].concat(data(teamList).map(t => `/dispatch/teams/${enc(t.id)}`))) {
      fixtures[`SCENARIO ${id} GET ${p}`] = await call('GET', p, null, tokens.admin_tech);
    }
  }

  // 1. 頁面實際送出的請求（只回放 GET，與頁面載入時的 POST 試算等）
  if (REQ_FILE) {
    const reqs = JSON.parse(fs.readFileSync(REQ_FILE, 'utf8'));
    // 每筆為 [method, path, body, role]；只回放 GET（寫入動作由 demo-mock.js 模擬）
    for (const [method, p, , role] of reqs) {
      if (method !== 'GET' || p === '/auth/me') continue;
      await record(method, p, null, tokens[role]);
    }
  }

  // 2. 列表與參考資料
  const simple = [
    '/orders', '/orders/categories', '/workers', '/crops', '/talents', '/brochures',
    '/calendar', '/calendar/harvests', '/calendar/quarters', '/calendar/months',
    '/insurance/types', '/insurance/workers', '/legalize/types', '/legalize/roster', '/legalize/summary',
    '/ratings/tags', '/ratings/teams', '/payments/methods', '/notifications',
    '/line/config', '/line/bindings', '/line/notify/status', '/public/config', '/auth/quick-profiles',
    '/dispatch/overview', '/dispatch/weather-state', '/dispatch/teams', '/attendance/summary/present-count',
    '/skills/workers'
  ];
  const got = {};
  for (const p of simple) got[p] = await record('GET', p);
  // 農場主視角的訂單／名冊
  await record('GET', '/legalize/roster', null, tokens.farmer);
  await record('GET', '/legalize/summary', null, tokens.farmer);
  await record('GET', '/farmers/me/past-workers', null, tokens.farmer);   // 指名回頭工人（不帶工期；能否排班由 demo-mock 依訂單推算）

  // 3. 明細
  const teams = data(got['/dispatch/teams']).map(t => t.id);
  for (const t of teams) {
    for (const p of [`/dispatch/teams/${enc(t)}`, `/insurance/teams/${enc(t)}`, `/skills/teams/${enc(t)}`,
      `/ratings/teams/${enc(t)}`]) await record('GET', p);
    // 領班評核：錄近 3 週，Mock 對沒資料的日期改用該工班最近一天有班次的資料
    for (let i = 0; i <= 21; i++) {
      const day = new Date(Date.now() + 8 * 3600e3 - i * 86400e3).toISOString().slice(0, 10);
      const r = await call('GET', `/evaluations/teams/${enc(t)}?date=${day}`, null, tokens.admin_tech);
      if (r.s < 400 && r.b && r.b.data && r.b.data.shifts && r.b.data.shifts.length) fixtures[`GET /evaluations/teams/${enc(t)}?date=${day}`] = r;
    }
  }
  for (const w of data(got['/workers'])) {
    const id = enc(w.id);
    for (const p of [`/workers/${id}`, `/workers/${id}/attendance`, `/workers/${id}/payroll`,
      `/attendance/today/${id}`, `/attendance/history/${id}`, `/insurance/workers/${id}`, `/skills/workers/${id}`]) await record('GET', p);
  }
  for (const o of data(got['/orders'])) {
    const id = enc(o.id);
    for (const p of [`/orders/${id}`, `/certificates/orders/${id}`, `/ratings/orders/${id}`,
      `/payments/${id}/status`, `/matching/orders/${id}`]) await record('GET', p);
    for (const t of teams) await record('GET', `/matching/orders/${id}/crew?teamId=${enc(t)}`);
    // 公開查驗（/public/certificates/{no}?code=）不錄：demo-mock.js 用上面證明資料內的 verifyCode 比對
  }
  for (const [list, prefix] of [['/crops', '/crops/'], ['/talents', '/talents/'], ['/brochures', '/brochures/']]) {
    for (const it of data(got[list])) await record('GET', prefix + enc(it.id));
  }

  // 錯誤回應（4xx/5xx）不存，讓 Mock 走通用 fallback
  let kept = 0, dropped = [];
  const out = {};
  for (const k of Object.keys(fixtures).sort()) {
    if (fixtures[k].s >= 400) { dropped.push(fixtures[k].s + ' ' + k.slice(0, 120)); continue; }
    out[k] = fixtures[k]; kept++;
  }
  const header = '/* 由 tools/record-mock-fixtures.js 自動產生，請勿手改。錄製時間：' + new Date().toISOString() + ' */\n';
  fs.writeFileSync(OUT, header + 'window.AGRI_FIXTURES = ' + JSON.stringify(out) + ';\n', 'utf8');
  console.log(`[record-mock] 已寫入 ${kept} 筆 → ${OUT}（${(fs.statSync(OUT).size / 1024).toFixed(0)} KB）`);
  if (dropped.length) console.log('[record-mock] 略過錯誤回應：\n  ' + dropped.join('\n  '));
})().catch(e => { console.error(e); process.exit(1); });
