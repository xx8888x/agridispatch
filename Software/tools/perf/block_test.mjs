import { chromium } from 'playwright-core';
import fs from 'node:fs';
const BASE = process.env.BASE || 'http://localhost:3000';
const browser = await chromium.launch({ executablePath: process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
const tk = await (await fetch(BASE + '/api/auth/demo-login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role: 'farmer' }) })).json();
const variants = [['基準（不封鎖）', null], ['封鎖 tailwindcss', /tailwindcss/], ['封鎖 lucide', /lucide/], ['封鎖 chart.js', /chart\.umd/], ['封鎖 Google Fonts', /fonts\.(googleapis|gstatic)/]];
const pages = [['web/AgriDispatch/farmer-login.html', false], ['web/AgriDispatch/farmer.html', true]];
const med = (a) => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)];
const out = [];
for (const [pg, auth] of pages) {
  for (const [name, re] of variants) {
    const runs = [];
    for (let i = 0; i < 5; i++) {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true });
      await ctx.addInitScript(([t, u, a]) => { if (a) { sessionStorage.setItem('agri_token', t); sessionStorage.setItem('agri_user', JSON.stringify(u)); }
        window.__p = { tbt: 0, lt: 0 }; new PerformanceObserver((l) => { for (const e of l.getEntries()) { window.__p.tbt += Math.max(0, e.duration - 50); window.__p.lt++; } }).observe({ type: 'longtask', buffered: true }); }, [tk.token, tk.user, auth]);
      const page = await ctx.newPage();
      const cdp = await ctx.newCDPSession(page); await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
      if (re) await page.route(re, (r) => r.abort());
      await page.goto(`${BASE}/${pg}`, { waitUntil: 'load', timeout: 60000 });
      await page.waitForTimeout(1500);
      const m = await page.evaluate(() => ({ fcp: (performance.getEntriesByName('first-contentful-paint')[0] || {}).startTime || 0, load: performance.getEntriesByType('navigation')[0].loadEventEnd, tbt: window.__p.tbt, lt: window.__p.lt }));
      runs.push(m); await ctx.close();
    }
    const row = { page: pg, variant: name, fcp: Math.round(med(runs.map((r) => r.fcp))), load: Math.round(med(runs.map((r) => r.load))), tbt: Math.round(med(runs.map((r) => r.tbt))), longTasks: med(runs.map((r) => r.lt)) };
    out.push(row); console.log(JSON.stringify(row));
  }
}
fs.writeFileSync((process.env.OUT || '.') + '/block.json', JSON.stringify(out, null, 2));
await browser.close();
