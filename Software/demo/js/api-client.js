/**
 * 智農派工 (AgriDispatch) - API Client / 認證包裝器
 * 依 Doc/03_認證與API安全規格.md 第 2、4、7 節實作：
 *   - 包裝 window.fetch，對同源 /api/ 或 API_BASE 開頭的請求自動加上 Authorization: Bearer <token>
 *   - demo 模式：無 token 時自動呼叫 /api/auth/demo-login（依頁面角色判斷），
 *     後端離線或非 demo 模式（404）時不影響既有離線假資料 fallback
 *   - production 模式：401 時清除 session 並導向 login.html；
 *     並提供 AgriAuth.requireRole() 供角色頁面在載入時做導向檢查
 * 需在頁面中於 js/app-config.js 之後、其他業務 script 之前載入。
 */
(function (global) {
  const CFG = global.AGRI_CONFIG || { mode: 'demo' };
  const MODE = CFG.mode === 'production' ? 'production' : 'demo';
  const API_BASE = CFG.apiBase || (global.location.origin.startsWith('http')
    ? `${global.location.origin}/api`
    : 'http://localhost:3000/api');

  const TOKEN_KEY = 'agri_token';
  const USER_KEY = 'agri_user';

  // ------------------------------------------------------------------
  // Session storage 存取
  // ------------------------------------------------------------------
  function getToken() {
    try { return sessionStorage.getItem(TOKEN_KEY) || null; } catch (e) { return null; }
  }
  function getUser() {
    try {
      const raw = sessionStorage.getItem(USER_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }
  function setSession(token, user) {
    try {
      if (token) sessionStorage.setItem(TOKEN_KEY, token);
      if (user) sessionStorage.setItem(USER_KEY, JSON.stringify(user));
    } catch (e) { /* 無痕模式等，忽略 */ }
  }
  function clearSession() {
    try {
      sessionStorage.removeItem(TOKEN_KEY);
      sessionStorage.removeItem(USER_KEY);
    } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------------------
  // 依目前頁面檔名判斷角色與登入頁（demo-login / requireRole 共用）
  // ------------------------------------------------------------------
  function detectPageRole() {
    const file = (global.location.pathname.split('/').pop() || '').toLowerCase();
    if (file.startsWith('admin')) return 'admin_tech';
    if (file.startsWith('farmer')) return 'farmer';
    if (file.startsWith('worker-foreign')) return 'worker_foreign';
    if (file.startsWith('worker-local')) return 'worker_local';
    return null;
  }
  function detectLoginPage() {
    const file = (global.location.pathname.split('/').pop() || '').toLowerCase();
    if (file.startsWith('admin')) return 'admin-login.html';
    if (file.startsWith('farmer')) return 'farmer-login.html';
    if (file.startsWith('worker-foreign')) return 'worker-foreign-login.html';
    if (file.startsWith('worker-local')) return 'worker-local-login.html';
    return 'login.html';
  }

  // ------------------------------------------------------------------
  // fetch 包裝：自動帶 token / demo 自動登入 / production 401 導向
  // ------------------------------------------------------------------
  const nativeFetch = global.fetch ? global.fetch.bind(global) : null;
  let demoLoginInflight = null;

  function resolveUrl(input) {
    if (typeof input === 'string') return input;
    if (input && typeof input.url === 'string') return input.url;
    return '';
  }

  function isApiRequest(url) {
    if (!url) return false;
    if (url.startsWith('/api/')) return true;
    if (API_BASE && url.startsWith(API_BASE)) return true;
    // 同源絕對網址也視為 API 請求
    try {
      const abs = new URL(url, global.location.href);
      return abs.origin === global.location.origin && abs.pathname.startsWith('/api/');
    } catch (e) { return false; }
  }

  function isAuthBootstrapEndpoint(url) {
    return /\/api\/auth\/(login|demo-login)(\?|$)/.test(url);
  }

  async function ensureDemoLogin() {
    if (getToken()) return getToken();
    if (demoLoginInflight) return demoLoginInflight;

    demoLoginInflight = (async () => {
      const role = detectPageRole();
      if (!role || !nativeFetch) return null;
      try {
        const res = await nativeFetch(`${API_BASE}/auth/demo-login`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ role })
        });
        if (res.ok) {
          const data = await res.json().catch(() => null);
          if (data && data.success && data.token) {
            setSession(data.token, data.user);
            return data.token;
          }
        }
        // 404（舊後端 / 非 demo 模式）或其他錯誤：沿用既有離線假資料 fallback，不丟例外
        return null;
      } catch (e) {
        // 後端離線：沿用既有離線假資料 fallback
        return null;
      }
    })();

    const token = await demoLoginInflight;
    demoLoginInflight = null;
    return token;
  }

  function goToLogin() {
    clearSession();
    const login = detectLoginPage();
    const file = (global.location.pathname.split('/').pop() || '').toLowerCase();
    if (file !== login.toLowerCase()) {
      global.location.href = login;
    }
  }

  if (nativeFetch) {
    global.fetch = async function (input, init) {
      const url = resolveUrl(input);
      const requestIsApi = isApiRequest(url);

      if (requestIsApi && !isAuthBootstrapEndpoint(url)) {
        let token = getToken();
        if (!token && MODE === 'demo') {
          token = await ensureDemoLogin();
        }
        if (token) {
          init = init || {};
          const headers = new Headers(init.headers || (typeof input !== 'string' && input ? input.headers : undefined) || {});
          if (!headers.has('Authorization')) {
            headers.set('Authorization', `Bearer ${token}`);
          }
          init = Object.assign({}, init, { headers });
        }
      }

      const response = await nativeFetch(input, init);

      if (requestIsApi && response.status === 401 && !isAuthBootstrapEndpoint(url)) {
        if (MODE === 'production') {
          goToLogin();
        }
        // demo 模式：401 交還呼叫端處理，沿用既有離線 fallback（避免打斷路演體驗）
      }

      return response;
    };
  }

  // ------------------------------------------------------------------
  // 角色頁面守門：production 模式才強制導向；demo 模式維持免密體驗
  // ------------------------------------------------------------------
  function requireRole(roles, loginPage) {
    if (MODE !== 'production') return true;
    const user = getUser();
    const token = getToken();
    const roleList = Array.isArray(roles) ? roles : (roles ? [roles] : null);
    const authorized = !!token && !!user && (!roleList || roleList.indexOf(user.role) !== -1);
    if (!authorized) {
      clearSession();
      global.location.href = loginPage || detectLoginPage();
      return false;
    }
    return true;
  }

  // ------------------------------------------------------------------
  // XSS 防護共用工具
  // ------------------------------------------------------------------
  function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value).replace(/[&<>"']/g, function (ch) {
      switch (ch) {
        case '&': return '&amp;';
        case '<': return '&lt;';
        case '>': return '&gt;';
        case '"': return '&quot;';
        case "'": return '&#39;';
        default: return ch;
      }
    });
  }

  global.AgriAuth = {
    MODE,
    API_BASE,
    getToken,
    getUser,
    setSession,
    clearSession,
    ensureDemoLogin,
    requireRole,
    detectPageRole,
    detectLoginPage
  };

  global.AgriUtil = global.AgriUtil || {};
  global.AgriUtil.escapeHtml = escapeHtml;
})(window);
