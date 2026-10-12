/**
 * 智農派工 (AgriDispatch) - 前端執行模式設定
 * 本檔案為「每份副本各自一份」，同步腳本 (tools/sync-frontend.js) 不會覆蓋它。
 * web/ 為 production 模式（真實帳密、Token 驗證、無後門）。
 * 詳見 07_系統分析及文件/03_認證與API安全規格.md 第 1 節。
 */
window.AGRI_CONFIG = {
  mode: 'production',
  // 功能開關：false 時隱藏該功能的所有入口（暫時下架）；改回 true 即恢復
  // legalize：工班合法化（黑轉白）轉換中心、後台彙總、LINE 黑轉白卡片
  features: { legalize: false },
  apiBase: window.location.origin.startsWith('http')
    ? `${window.location.origin}/api`
    : 'http://localhost:3000/api'
};
