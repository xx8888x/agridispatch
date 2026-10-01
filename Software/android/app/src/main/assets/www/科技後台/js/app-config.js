/**
 * 智農派工 (AgriDispatch) - 前端執行模式設定
 * 本檔案為「每份副本各自一份」，同步腳本 (tools/sync-frontend.js) 不會覆蓋它。
 * demo/ 與 android www/ 為 demo 模式（免密展示、可重灌資料）；
 * web/ 為 production 模式（真實帳密、Token 驗證、無後門）。
 * 詳見 07_系統分析及文件/03_認證與API安全規格.md 第 1 節。
 */
window.AGRI_CONFIG = {
  mode: 'demo', // 'demo' | 'production'
  apiBase: window.location.origin.startsWith('http')
    ? `${window.location.origin}/api`
    : 'http://localhost:3000/api'
};
