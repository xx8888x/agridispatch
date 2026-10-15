// 前端頁面效能測試（Playwright + 系統 Chrome，headless）
// 環境變數：BASE（預設 http://localhost:3000）、OUT（結果 JSON 目錄）、SHOTS（截圖輸出根目錄）、MODE=desktop|mobile|all
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright-core';

const BASE = process.env.BASE || 'http://localhost:3000';
const OUT = process.env.OUT;
const SHOTS = process.env.SHOTS;
const WHICH = process.env.MODE || 'all';
const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';

// 頁面清單：[站台, 路徑, 角色（web 正式版需帶登入 token；demo 免）]
const PAGES = [
  ['AgriDispatch/index.html', null], ['AgriDispatch/login.html', null], ['AgriDispatch/farmer-login.html', null], ['AgriDispatch/farmer-register.html', null],
  ['AgriDispatch/worker-local-login.html', null], ['AgriDispatch/brochure.html', null], ['AgriDispatch/pitch.html', null], ['AgriDispatch/farmers_map.html', 'farmer'],
  ['AgriDispatch/farmer.html', 'farmer'], ['AgriDispatch/rosters.html', 'farmer'], ['AgriDispatch/expenses.html', 'farmer'], ['AgriDispatch/account.html', 'farmer'],
  ['AgriDispatch/farmer-certificate.html', 'farmer'], ['AgriDispatch/worker-local.html', 'worker_local'], ['AgriDispatch/worker-skills.html', 'worker_local'],
  ['AgriDispatch/worker-local-evaluation.html', 'worker_local'],
  ['TechBackend/admin-login.html', null], ['TechBackend/admin.html', 'admin_tech'], ['TechBackend/vue-crud.html', 'admin_tech'], ['TechBackend/insurance.html', 'admin_tech'],
  ['TechBackend/matching.html', 'admin_tech'], ['TechBackend/payroll.html', 'admin_tech'], ['TechBackend/leaves.html', 'admin_tech'], ['TechBackend/accounts.html', 'admin_tech'],
  ['TechBackend/skills.html', 'admin_tech'], ['TechBackend/rosters.html', 'admin_tech'],
];
const MOBILE_PAGES = ['AgriDispatch/index.html', 'AgriDispatch/farmer-login.html', 'AgriDispatch/farmer.html', 'AgriDispatch/worker-local.html', 'TechBackend/admin.html', 'AgriDispatch/brochure.html'];

async function demoToken(role) {
  const r = await fetch(`${BASE}/api/auth/demo-login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role }) });
  return r.json();
}

const INIT = () => {
  window.__p = { lcp: 0, cls: 0, tbt: 0, longTasks: 0, fid: 0 };
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__p.lcp = e.startTime; }).observe({ type: 'largest-contentful-paint', buffered: true }); } catch (e) {}
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) if (!e.hadRecentInput) window.__p.cls += e.value; }).observe({ type: 'layout-shift', buffered: true }); } catch (e) {}
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) { window.__p.longTasks++; window.__p.tbt += Math.max(0, e.duration - 50); } }).observe({ type: 'longtask', buffered: true }); } catch (e) {}
};

const COLLECT = () => {
  const nav = performance.getEntriesByType('navigation')[0] || {};
  const fcp = (performance.getEntriesByName('first-contentful-paint')[0] || {}).startTime || 0;
  const res = performance.getEntriesByType('resource');
  const host = location.host;
  const sum = (k) => res.reduce((a, r) => a + (r[k] || 0), 0);
  const byType = {};
  res.forEach((r) => { const t = r.initiatorType || 'other'; byType[t] = byType[t] || { n: 0, bytes: 0 }; byType[t].n++; byType[t].bytes += r.transferSize || r.encodedBodySize || 0; });
  const api = res.filter((r) => r.name.includes('/api/')).map((r) => ({ url: r.name.replace(location.origin, ''), ms: Math.round(r.duration), ttfb: Math.round(r.responseStart - r.requestStart), bytes: r.transferSize || r.encodedBodySize || 0 }));
  const top = [...res].sort((a, b) => (b.decodedBodySize || 0) - (a.decodedBodySize || 0)).slice(0, 5).map((r) => ({ url: r.name.replace(location.origin, ''), kb: Math.round((r.decodedBodySize || 0) / 1024), ms: Math.round(r.duration) }));
  const slow = [...res].sort((a, b) => b.duration - a.duration).slice(0, 3).map((r) => ({ url: r.name.replace(location.origin, ''), ms: Math.round(r.duration) }));
  const ext = res.filter((r) => { try { return new URL(r.name).host !== host; } catch (e) { return false; } }).map((r) => r.name);
  return {
    ttfb: Math.round(nav.responseStart || 0), dcl: Math.round(nav.domContentLoadedEventEnd || 0), load: Math.round(nav.loadEventEnd || 0),
    fcp: Math.round(fcp), lcp: Math.round(window.__p.lcp), cls: +window.__p.cls.toFixed(3), tbt: Math.round(window.__p.tbt), longTasks: window.__p.longTasks,
    reqs: res.length + 1, docKB: Math.round((nav.decodedBodySize || 0) / 1024),
    transferKB: Math.round((sum('transferSize') + (nav.transferSize || 0)) / 1024), decodedKB: Math.round((sum('decodedBodySize') + (nav.decodedBodySize || 0)) / 1024),
    byType, api, top, slow, ext,
    heapMB: performance.memory ? +(performance.memory.usedJSHeapSize / 1048576).toFixed(1) : null,
    dom: document.getElementsByTagName('*').length,
  };
};

const med = (a) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : 0; };

async function visit(browser, site, rel, role, opt) {
  const { mobile, throttle, cold, shot, tag } = opt;
  const ctxOpts = mobile
    ? { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, userAgent: 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36' }
    : { viewport: { width: 1440, height: 900 } };
  const ctx = opt.ctx || await browser.newContext(ctxOpts);
  const own = !opt.ctx;
  if (own && site === 'web' && role) {
    const tk = await demoToken(role);
    await ctx.addInitScript(([t, u]) => { try { sessionStorage.setItem('agri_token', t); sessionStorage.setItem('agri_user', JSON.stringify(u)); } catch (e) {} }, [tk.token, tk.user]);
  }
  if (own) await ctx.addInitScript(INIT);
  const page = opt.page || await ctx.newPage();
  const errors = []; const failed = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 120) + ' @' + ((m.location() || {}).url || '').replace(BASE, '')); });
  page.on('pageerror', (e) => errors.push('PAGEERROR ' + String(e).slice(0, 200)));
  page.on('requestfailed', (r) => failed.push(r.url().replace(BASE, '') + ' ' + (r.failure() || {}).errorText));
  page.on('response', (r) => { if (r.status() >= 400) failed.push(r.url().replace(BASE, '') + ' HTTP ' + r.status()); });
  let cdp = null;
  if (throttle && own) {
    cdp = await ctx.newCDPSession(page);
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 150, downloadThroughput: 1.6 * 1024 * 1024 / 8, uploadThroughput: 750 * 1024 / 8 });
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  }
  const url = `${BASE}/${site}/${rel}`;
  const t0 = Date.now();
  let status = 0;
  try {
    if (opt.reload) { const r = await page.reload({ waitUntil: 'load', timeout: 60000 }); status = r ? r.status() : 0; }
    else { const r = await page.goto(url, { waitUntil: 'load', timeout: 60000 }); status = r ? r.status() : 0; }
    await page.waitForLoadState('networkidle', { timeout: throttle ? 30000 : 10000 }).catch(() => {});
    await page.waitForTimeout(800);
  } catch (e) { errors.push('NAV ' + String(e).slice(0, 160)); }
  const wall = Date.now() - t0;
  const m = await page.evaluate(COLLECT).catch(() => ({}));
  if (shot) {
    fs.mkdirSync(path.dirname(shot), { recursive: true });
    await page.screenshot({ path: shot, type: 'jpeg', quality: 82, fullPage: false }).catch((e) => errors.push('SHOT ' + String(e).slice(0, 100)));
  }
  const result = { site, page: rel, role, status, wall, ...m, errors: [...new Set(errors)], failed: [...new Set(failed)], finalUrl: page.url().replace(BASE, '') };
  if (own) await ctx.close();
  return { result, ctx, page };
}

function agg(runs) {
  const keys = ['ttfb', 'dcl', 'load', 'fcp', 'lcp', 'cls', 'tbt', 'wall'];
  const o = { ...runs[0] };
  for (const k of keys) o[k] = +med(runs.map((r) => r[k] || 0)).toFixed(3);
  o.errors = [...new Set(runs.flatMap((r) => r.errors))]; o.failed = [...new Set(runs.flatMap((r) => r.failed))];
  return o;
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ['--disable-extensions', '--no-first-run', '--enable-precise-memory-info'] });
const results = { startedAt: new Date().toISOString(), base: BASE, chrome: browser.version(), desktop: [], mobile: [] };
const save = () => fs.writeFileSync(OUT + '/browser.json', JSON.stringify(results, null, 2));

for (const site of (process.env.SITES || 'demo,web').split(',')) {
  if (WHICH === 'mobile') break;
  for (const [rel, role] of PAGES) {
    if (process.env.ONLY && !new RegExp(process.env.ONLY).test(rel)) continue;
    const name = rel.replace('.html', '').replace('/', '_');
    const cold = [];
    for (let i = 0; i < 3; i++) {
      const { result } = await visit(browser, site, rel, role, { shot: i === 0 ? `${SHOTS}/desktop/${site}_${name}.jpg` : null });
      cold.push(result);
    }
    // 暖快取：同一個 context 連續 reload
    const warm = [];
    // 暖載入：持有同一個 context，第一次載入暖機快取後 reload 兩次
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    if (site === 'web' && role) { const tk = await demoToken(role); await ctx.addInitScript(([t, u]) => { try { sessionStorage.setItem('agri_token', t); sessionStorage.setItem('agri_user', JSON.stringify(u)); } catch (e) {} }, [tk.token, tk.user]); }
    await ctx.addInitScript(INIT);
    const pg = await ctx.newPage();
    await visit(browser, site, rel, role, { ctx, page: pg });
    for (let i = 0; i < 2; i++) { const r = await visit(browser, site, rel, role, { ctx, page: pg, reload: true }); warm.push(r.result); }
    await ctx.close();
    const c = agg(cold), w = agg(warm);
    results.desktop.push({ site, page: rel, role, cold: c, warm: { ttfb: w.ttfb, dcl: w.dcl, load: w.load, fcp: w.fcp, lcp: w.lcp, tbt: w.tbt, transferKB: w.transferKB, reqs: w.reqs, wall: w.wall } });
    save();
    console.log(`[desktop] ${site}/${rel.padEnd(42)} cold FCP ${c.fcp} LCP ${c.lcp} load ${c.load} TBT ${c.tbt} | ${c.transferKB}KB ${c.reqs} req | warm load ${w.load} FCP ${w.fcp} ${w.transferKB}KB | err ${c.errors.length} fail ${c.failed.length} ${c.status}`);
  }
}

for (const site of (process.env.SITES || 'demo,web').split(',')) {
  if (WHICH === 'desktop') break;
  for (const rel of MOBILE_PAGES) {
    if (process.env.ONLY && !new RegExp(process.env.ONLY).test(rel)) continue;
    const role = PAGES.find((p) => p[0] === rel)[1];
    const name = rel.replace('.html', '').replace('/', '_');
    const runs = [];
    for (let i = 0; i < 2; i++) {
      const { result } = await visit(browser, site, rel, role, { mobile: true, throttle: true, shot: i === 0 ? `${SHOTS}/mobile/${site}_${name}.jpg` : null });
      runs.push(result);
    }
    const c = agg(runs);
    results.mobile.push({ site, page: rel, role, cold: c });
    save();
    console.log(`[mobile 4G x4cpu] ${site}/${rel.padEnd(40)} FCP ${c.fcp} LCP ${c.lcp} load ${c.load} TBT ${c.tbt} | ${c.transferKB}KB ${c.reqs} req | err ${c.errors.length}`);
  }
}
results.finishedAt = new Date().toISOString(); save();
await browser.close();
console.log('DONE');
