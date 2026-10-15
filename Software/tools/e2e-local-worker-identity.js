/**
 * 本國工人工作台身分檢查（瀏覽器 E2E）：以「非示範工號」的真實帳號登入正式版前端（/web/），
 * 確認工作台依帳號的 subRole、姓名與工號呈現，不顯示示範人物（林小祥／陳建宏／張志明）、
 * 沒有 403、打卡送出的是本人工號。對應 07_系統分析及文件／功能測試報告 B-01。
 *
 * 前置：
 *   1. 後端已啟動且有示範資料（demo profile，或 DEV 環境）；
 *   2. 安裝 playwright-core（不隨專案安裝）：npm i playwright-core（在任一資料夾），執行時用 NODE_PATH 指到它的 node_modules；
 *   3. 本機有 Chrome（或用環境變數 CHROME_PATH 指定）。
 *
 * 用法（從專案根目錄）：
 *   node Software/tools/e2e-local-worker-identity.js [後端網址，預設 http://localhost:3000]
 *   環境變數：AGRI_TEST_PASSWORD（工人密碼，預設 123456；DEV 環境為 a123456）、CHROME_PATH
 *
 * 會對測試帳號實際按「上班打卡」並寫入出勤紀錄，請只對測試／示範資料庫執行。
 */
let chromium;
try { ({ chromium } = require('playwright-core')); } catch (e) {
  console.error('找不到 playwright-core。請先 npm i playwright-core，並以 NODE_PATH 指到其 node_modules。');
  process.exit(2);
}

const BASE = (process.argv[2] || 'http://localhost:3000').replace(/\/$/, '');
const PASSWORD = process.env.AGRI_TEST_PASSWORD || '123456';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const SECTION = { foreman: 'sectionForemanTrack', machinery: 'sectionMachineryTrack', labor: 'sectionLaborTrack' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// tab：故意選「錯的」登入分頁，確認身分軌不受登入頁分頁影響。forbid：不得出現在身分欄位的他人姓名。
const CASES = [
  { id: 'W025', phone: '0933-555-666', tab: '機耕師', name: '張天成', track: 'machinery', forbid: ['陳建宏', '林小祥', '張志明'] },
  { id: 'W026', phone: '0918-668-999', tab: '勞務員', name: '林小祥', track: 'foreman', forbid: ['張志明', '陳建宏'] },
  { id: 'W031', phone: '0921-310-031', tab: '機耕師', name: '王秀英', track: 'labor', forbid: ['張志明', '陳建宏', '林小祥'] }
];

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  let failed = 0;
  for (const c of CASES) {
    const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-TW' })).newPage();
    const errors = [];
    const clockPosts = [];
    page.on('response', (r) => { if (r.status() >= 400 && r.url().includes('/api/')) errors.push(`${r.status()} ${r.url().replace(BASE, '')}`); });
    page.on('pageerror', (e) => errors.push('pageerror ' + e));
    page.on('request', (q) => { if (q.method() === 'POST' && /clock-in/.test(q.url())) clockPosts.push(q.postData() || ''); });

    await page.goto(`${BASE}/web/AgriDispatch/worker-local-login.html`);
    await sleep(1200);
    await page.locator(`button:has-text("${c.tab}")`).first().click();
    await page.fill('#loginId', c.phone);
    await page.fill('#loginPwd', PASSWORD);
    await page.click('button[type=submit]');
    const enter = page.locator('a:has-text("進入工作台")').first();
    await enter.waitFor({ timeout: 10000 });
    const href = await enter.getAttribute('href');
    await enter.click();
    await sleep(3500);

    const ids = await page.evaluate(() => {
      const t = (id) => { const e = document.getElementById(id); return e && e.getBoundingClientRect().width ? e.textContent : ''; };
      return {
        who: t('topBarUserName'), role: t('topBarUserRole'), avatar: t('header-avatar'),
        cards: ['fm-card-name', 'mc-card-name', 'lb-card-name'].map(t).join(''),
        visible: ['sectionForemanTrack', 'sectionMachineryTrack', 'sectionLaborTrack'].filter((s) => !document.getElementById(s).classList.contains('hidden'))
      };
    });
    const idText = ids.who + ids.role + ids.avatar + ids.cards;
    const leaks = c.forbid.filter((n) => idText.includes(n));

    const problems = [];
    if (!href.includes('role=' + c.track)) problems.push(`進入工作台連結 role 不是 ${c.track}：${href}`);
    if (!idText.includes(c.name)) problems.push(`身分欄位沒有本人姓名「${c.name}」：${idText}`);
    if (leaks.length) problems.push(`身分欄位出現他人姓名：${leaks.join('、')}`);
    if (ids.visible.join() !== SECTION[c.track]) problems.push(`顯示的身分區塊不是 ${SECTION[c.track]}：${ids.visible.join()}`);
    const bad = errors.filter((e) => /^(403|401|5\d\d|pageerror)/.test(e));
    if (bad.length) problems.push(`API 錯誤：${bad.join('；')}`);

    // 打卡：能按就按，確認送出的是本人工號（已在作業中則略過）
    const inBtn = page.locator(c.track === 'foreman' ? '#foremanClockInBtn' : c.track === 'machinery' ? '#localClockInBtn' : 'button:visible:has-text("上班打卡")').first();
    if (await inBtn.count() && !(await inBtn.isDisabled())) {
      await inBtn.click();
      await sleep(1500);
      if (!clockPosts.some((p) => p.includes(`"workerId":"${c.id}"`))) problems.push(`打卡沒有送出本人工號 ${c.id}：${clockPosts.join(' | ') || '（沒有請求）'}`);
    }

    console.log(`${problems.length ? 'FAIL' : 'PASS'}  ${c.id} ${c.name}（登入頁分頁：${c.tab}）→ ${ids.who}／${ids.role}`);
    problems.forEach((p) => console.log('      - ' + p));
    if (problems.length) failed++;
    await page.context().close();
  }
  await browser.close();
  console.log(failed ? `\n${failed} 個帳號未通過` : '\n全部通過');
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
