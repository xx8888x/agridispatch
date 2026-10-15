// 把 results/*.json 轉成報告用的 Markdown 表格片段（stdout）
import fs from 'node:fs';
const R = process.env.OUT;
const load = JSON.parse(fs.readFileSync(R + '/load.json', 'utf8'));
const probe = JSON.parse(fs.readFileSync(R + '/probe.json', 'utf8'));
const br = JSON.parse(fs.readFileSync(R + '/browser.json', 'utf8'));
let gz = null; try { gz = JSON.parse(fs.readFileSync(R + '/gzip/browser.json', 'utf8')); } catch (e) {}
let polluted = null; try { polluted = JSON.parse(fs.readFileSync(R + '/browser_polluted_smoke.json', 'utf8')); } catch (e) {}
const f = (n, d = 0) => (n == null ? '-' : Number(n).toFixed(d));
const P = (name) => load.phases.find((p) => p.name === name);
const out = [];
const h = (s) => out.push('\n' + s + '\n');

h('### A1. 各 API 單一使用者延遲與 50 並發（server 端，localhost）');
out.push('| API | 單人 p50 (ms) | 單人 p95 (ms) | 50 並發 req/s | 50 並發 p50 (ms) | p95 (ms) | p99 (ms) | 錯誤 |');
out.push('|---|---:|---:|---:|---:|---:|---:|---:|');
for (const p of load.phases.filter((p) => p.kind === 'endpoint')) {
  out.push(`| \`${p.name}\` | ${p.c1.p50} | ${p.c1.p95} | ${f(p.c50.rps)} | ${p.c50.p50} | ${p.c50.p95} | ${p.c50.p99} | ${p.c50.err} |`);
}

h('### B. 混合流量階梯（15 秒／階，所有角色混合，含 4% 試算與 1% 媒合）');
out.push('| 並發使用者 | req/s | p50 (ms) | p95 (ms) | p99 (ms) | 最慢 (ms) | 錯誤 |');
out.push('|---:|---:|---:|---:|---:|---:|---:|');
for (const r of P('B:mix-ramp').ramp) out.push(`| ${r.c} | ${f(r.rps)} | ${r.p50} | ${r.p95} | ${r.p99} | ${f(r.max)} | ${r.err}/${r.n} |`);

h('### C. 重量端點（8 秒／階）');
out.push('| 端點 | 並發 | req/s | p50 (ms) | p95 (ms) | 最慢 (ms) |');
out.push('|---|---:|---:|---:|---:|---:|');
for (const [name, rows] of Object.entries(P('C:heavy').heavy)) for (const r of rows) out.push(`| \`${name}\` | ${r.c} | ${f(r.rps, 1)} | ${r.p50} | ${r.p95} | ${f(r.max)} |`);

h('### D. 登入（PBKDF2 310,000 次）');
out.push('| 並發 | 登入/秒 | p50 (ms) | p95 (ms) |');
out.push('|---:|---:|---:|---:|');
const D = P('D:login');
for (const r of D.loginRes) out.push(`| ${r.c} | ${r.rps} | ${f(r.p50)} | ${f(r.p95)} |`);
out.push(`\n登入洪泛（12 並發連續登入）期間，其它 API（\`GET /api/orders\`，10 並發）p50 ${D.interference.baseline.p50} → ${D.interference.during.p50} ms、p95 ${D.interference.baseline.p95} → ${D.interference.during.p95} ms。`);

h('### E. 寫入與資料量成長');
const E = P('E:write-growth');
out.push('| 訂單總數 | 農場主列表 p50 (ms) | 列表 p95 (ms) | 列表回應大小 | 後台列表 p50 (ms) |');
out.push('|---:|---:|---:|---:|---:|');
for (const g of E.grow) out.push(`| ${g.orders} | ${g.farmer.p50} | ${g.farmer.p95} | ${(g.farmerBytes / 1024).toFixed(0)} KB | ${g.admin.p50} |`);
out.push('\n| 批次 | 8 並發建立訂單 req/s | p50 (ms) | p95 (ms) | 錯誤 |');
out.push('|---|---:|---:|---:|---:|');
let acc = 16; for (const w of E.writes) { out.push(`| +${w.batch}（${acc} → ${acc + w.batch} 筆）| ${w.rps} | ${w.p50} | ${w.p95} | ${w.err} |`); acc += w.batch; }

h('### F. 靜態檔（由 Spring Boot 直接提供，無壓縮）');
out.push('| 檔案 | 大小 | 20 並發 req/s | p50 (ms) | 100 並發 req/s | p50 (ms) | p95 (ms) |');
out.push('|---|---:|---:|---:|---:|---:|---:|');
for (const [p, rows] of Object.entries(P('F:static').statics)) {
  const a = rows.find((r) => r.c === 20), b = rows.find((r) => r.c === 100);
  out.push(`| \`${p}\` | ${(a.avgBytes / 1024).toFixed(0)} KB | ${f(a.rps)} | ${a.p50} | ${f(b.rps)} | ${b.p50} | ${b.p95} |`);
}

h('### G. 浸泡（100 並發混合流量 60 秒）');
out.push('| 時間 | req/s | p50 (ms) | p95 (ms) | p99 (ms) | 錯誤 |');
out.push('|---:|---:|---:|---:|---:|---:|');
P('G:soak').soak.forEach((r, i) => out.push(`| ${(i + 1) * 10} s | ${f(r.rps)} | ${r.p50} | ${r.p95} | ${r.p99} | ${r.err} |`));

// 前端
const med = (a) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : 0; };
function siteStats(site) {
  const rows = br.desktop.filter((r) => r.site === site);
  return { n: rows.length, fcp: med(rows.map((r) => r.cold.fcp)), lcp: med(rows.map((r) => r.cold.lcp)), load: med(rows.map((r) => r.cold.load)), tbt: med(rows.map((r) => r.cold.tbt)),
    kb: med(rows.map((r) => r.cold.transferKB)), reqs: med(rows.map((r) => r.cold.reqs)), wload: med(rows.map((r) => r.warm.load)), wfcp: med(rows.map((r) => r.warm.fcp)) };
}
h('### H1. 桌面瀏覽器（Chrome 1440×900，無節流）各頁中位數');
out.push('| 站台 | 頁數 | 冷載入 FCP (ms) | LCP (ms) | load (ms) | TBT (ms) | 傳輸量 (KB) | 請求數 | 暖載入 load (ms) |');
out.push('|---|---:|---:|---:|---:|---:|---:|---:|---:|');
for (const s of ['demo', 'web']) { const x = siteStats(s); out.push(`| ${s} | ${x.n} | ${x.fcp} | ${x.lcp} | ${x.load} | ${x.tbt} | ${x.kb} | ${x.reqs} | ${x.wload} |`); }

h('### H2. 桌面逐頁明細（冷載入 3 次中位數；暖載入＝同一分頁 reload）');
out.push('| 站台 | 頁面 | FCP | LCP | load | TBT | CLS | 傳輸 KB | 請求 | API 數 | 暖 load | 主控台錯誤 |');
out.push('|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|');
for (const r of br.desktop) { const c = r.cold; out.push(`| ${r.site} | ${r.page} | ${c.fcp} | ${c.lcp} | ${c.load} | ${c.tbt} | ${c.cls} | ${c.transferKB} | ${c.reqs} | ${(c.api || []).length} | ${r.warm.load} | ${c.errors.length} |`); }

h('### I. 手機模擬（Chrome 390×844、4× CPU 降速、4G 1.6 Mbps／RTT 150 ms，冷載入 2 次中位數）');
out.push('| 站台 | 頁面 | FCP (s) | LCP (s) | load (s) | TBT (s) | 傳輸 KB | 請求 |' + (gz ? ' 開 gzip 後 FCP (s) | LCP (s) | load (s) | 傳輸 KB |' : ''));
out.push('|---|---|---:|---:|---:|---:|---:|---:|' + (gz ? '---:|---:|---:|---:|' : ''));
for (const r of br.mobile) {
  const c = r.cold; let tail = '';
  if (gz) { const g = gz.mobile.find((x) => x.site === r.site && x.page === r.page); tail = g ? ` ${(g.cold.fcp / 1000).toFixed(1)} | ${(g.cold.lcp / 1000).toFixed(1)} | ${(g.cold.load / 1000).toFixed(1)} | ${g.cold.transferKB} |` : ' - | - | - | - |'; }
  out.push(`| ${r.site} | ${r.page} | ${(c.fcp / 1000).toFixed(1)} | ${(c.lcp / 1000).toFixed(1)} | ${(c.load / 1000).toFixed(1)} | ${(c.tbt / 1000).toFixed(1)} | ${c.transferKB} | ${c.reqs} |` + tail);
}

if (polluted) {
  h('### J. 資料量大時的前端（farmer 有 1,520 筆訂單時的 web/farmer.html）');
  const r = polluted.desktop.find((x) => x.site === 'web' && x.page.endsWith('farmer.html'));
  const a = r.cold.api.sort((x, y) => y.bytes - x.bytes)[0];
  out.push(`冷載入 load ${r.cold.load} ms、傳輸 ${r.cold.transferKB} KB；最大請求 \`${a.url}\` ${(a.bytes / 1024).toFixed(0)} KB。`);
}

h('### K. 各頁最重資源（桌面 web，前 3 名，解壓後 KB）');
const tops = {};
for (const r of br.desktop.filter((x) => x.site === 'web')) for (const t of r.cold.top.slice(0, 3)) { const k = t.url.replace(/\?.*$/, '').slice(0, 70); tops[k] = (tops[k] || 0) + 1; }
out.push('| 資源 | 出現在幾頁的前 3 名 |'); out.push('|---|---:|');
for (const [k, v] of Object.entries(tops).sort((a, b) => b[1] - a[1]).slice(0, 8)) out.push(`| \`${k}\` | ${v} |`);

h('### L. 每次請求的 SQL 語句數（Hibernate show-sql，暖機後單次請求）');
const sq = JSON.parse(fs.readFileSync(R + '/sqlcount.json', 'utf8'));
out.push('| 端點 | SQL 語句數 |'); out.push('|---|---:|');
for (const r of sq) out.push(`| \`${r.endpoint}\` | ${r.sql} |`);

h('### M. 封鎖單一第三方腳本的前端實驗（手機視窗、4× CPU 降速、無網路節流，5 次中位數）');
const bl = JSON.parse(fs.readFileSync(R + '/block.json', 'utf8'));
out.push('| 頁面 | 情境 | FCP (ms) | load (ms) | TBT (ms) | 長工作數 |'); out.push('|---|---|---:|---:|---:|---:|');
for (const r of bl) out.push(`| ${r.page.replace('web/', '')} | ${r.variant} | ${r.fcp} | ${r.load} | ${r.tbt} | ${r.longTasks} |`);

h('### N. 伺服器資源（壓測期間約每 2 秒取樣，JVM 行程）');
const rows = fs.readFileSync(R + '/resources.csv', 'utf8').split(String.fromCharCode(10)).map((l) => l.trim().replace(String.fromCharCode(0xFEFF), '')).filter(Boolean).slice(1).map((l) => l.split(','));
const w0 = new Date(load.startedAt).toTimeString().slice(0, 8), w1 = new Date(load.finishedAt).toTimeString().slice(0, 8);
const win = rows.filter((r) => r[0] >= w0 && r[0] <= w1);
rows.length = 0; win.forEach((r) => rows.push(r));
const cpu = rows.map((r) => +r[2]), ws = rows.map((r) => +r[3]), th = rows.map((r) => +r[4]), sy = rows.map((r) => +r[5]);
const avg = (a) => a.reduce((x, y) => x + y, 0) / a.length;
out.push('| 指標 | 數值 |'); out.push('|---|---:|');
out.push(`| 取樣數（約每 2 秒一筆，僅壓測 A–G 期間） | ${rows.length} |`);
out.push(`| JVM CPU（佔整機 12 邏輯核心） 平均／峰值 | ${avg(cpu).toFixed(1)}% ／ ${Math.max(...cpu)}% |`);
out.push(`| 整機 CPU 平均 | ${avg(sy).toFixed(1)}% |`);
out.push(`| JVM 工作集 起點／峰值／結束 | ${ws[0]} MB ／ ${Math.max(...ws)} MB ／ ${ws[ws.length - 1]} MB |`);
out.push(`| 執行緒數 峰值 | ${Math.max(...th)} |`);

fs.writeFileSync(R + '/tables.md', out.join('\n'));
console.log('ok', out.length, 'lines');
