/**
 * 智農派工 (AgriDispatch) - 前端執行模式設定
 * 本檔案為「每份副本各自一份」，同步腳本 (tools/sync-frontend.js) 不會覆蓋它。
 * web/ 為 production 模式（真實帳密、Token 驗證、無後門）。
 * 詳見 07_系統分析及文件/03_認證與API安全規格.md 第 1 節。
 */
window.AGRI_CONFIG = {
  mode: 'production',
  apiBase: window.location.origin.startsWith('http')
    ? `${window.location.origin}/api`
    : 'http://localhost:3000/api',
  // LINE 帳號綁定頁（line-bind.html）的 LIFF ID；空字串時：demo 模式用模擬 LINE 帳號、production 顯示「請從 LINE 開啟」
  liffBindId: ''
};
