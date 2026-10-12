/**
 * 智農派工 (AgriDispatch) - 前端執行模式設定
 * 本檔案為「每份副本各自一份」，同步腳本 (tools/sync-frontend.js) 不會覆蓋它。
 * demo/ 與 android www/ 為 demo 模式（免密展示、可重灌資料）；
 * web/ 為 production 模式（真實帳密、Token 驗證、無後門）。
 * 詳見 07_系統分析及文件/03_認證與API安全規格.md 第 1 節。
 */
// Android App：原生端（WebAppInterface.getBackendConfig，值來自建置設定 API_BASE／APP_MODE）提供後端網址時，
// 關閉 Mock、改連該後端；未提供（目前正式版，正式網域上線前）則沿用內建示範資料。網站沒有原生橋接，不受影響。
var AGRI_APP_BACKEND = (function () {
  try {
    var c = window.AgriNativeBridge && window.AgriNativeBridge.getBackendConfig
      ? JSON.parse(window.AgriNativeBridge.getBackendConfig()) : null;
    return c && c.apiBase ? c : null;
  } catch (e) { return null; }
})();

window.AGRI_CONFIG = {
  mode: AGRI_APP_BACKEND ? AGRI_APP_BACKEND.mode : 'demo', // 'demo' | 'production'
  // 純前端示範：true 時 /api/* 由 ../mock/ 的錄製資料回應，不需啟動後端；改 false 則連真實後端
  mock: !AGRI_APP_BACKEND,
  // 功能開關：false 時隱藏該功能的所有入口（路演暫時下架）；改回 true 即恢復
  // legalize：工班合法化（黑轉白）轉換中心、後台彙總、LINE 黑轉白卡片
  // foreignWorkers：外籍移工（登入入口、外勞工作頁、LINE 綁定外籍身分、後台外勞示範帳號）；先以本國工人為主
  features: { legalize: false, foreignWorkers: false },
  apiBase: AGRI_APP_BACKEND ? AGRI_APP_BACKEND.apiBase : window.location.origin.startsWith('http')
    ? `${window.location.origin}/api`
    : 'http://localhost:3000/api'
};

// 純前端 Mock：須在 api-client.js 之前載入（localStorage.agri_mock = 'record' 可改成錄製模式）
if (window.AGRI_CONFIG.mock || (function () { try { return localStorage.getItem('agri_mock'); } catch (e) { return null; } })()) {
  document.write('<script src="../mock/demo-fixtures.js"><\/script><script src="../mock/demo-mock.js"><\/script>');
}
