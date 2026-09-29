/**
 * ?箄噙瘣曉極 (AgriDispatch) - ?垢?瑁?璅∪?閮剖?
 * ?祆?獢??隞賢?砍??芯?隞賬??郊?單 (tools/sync-frontend.js) 銝?閬?摰?
 * demo/ ??android www/ ??demo 璅∪?嚗?撖?蝷箝??鞈?嚗?
 * web/ ??production 璅∪?嚗?撖血董撖oken 撽??敺?嚗?
 * 閰唾? Doc/03_隤??PI摰閬.md 蝚?1 蝭??
 */
window.AGRI_CONFIG = {
  mode: 'production', // 'demo' | 'production'
  apiBase: window.location.origin.startsWith('http')
    ? `${window.location.origin}/api`
    : 'http://localhost:3000/api'
};

