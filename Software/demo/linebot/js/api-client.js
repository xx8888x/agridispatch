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
    if (file.startsWith('vue-crud')) return 'admin_tech'; // 舊版管理頁
    if (file.startsWith('insurance')) return 'admin_tech'; // 保險登錄（科技後台）
    if (file.startsWith('skills')) return 'admin_tech'; // 技能標籤複核（科技後台）
    if (file.startsWith('matching')) return 'admin_tech'; // 派工媒合（科技後台）
    if (file.startsWith('worker-skills')) return 'worker_local'; // 工人技能進度（demo 預設帶工領班）
    if (file.startsWith('farmer')) return 'farmer';
    if (file.startsWith('worker-foreign')) return 'worker_foreign';
    if (file.startsWith('worker-local')) return 'worker_local';
    return null;
  }
  function detectLoginPage() {
    const file = (global.location.pathname.split('/').pop() || '').toLowerCase();
    if (file.startsWith('admin') || file.startsWith('vue-crud') || file.startsWith('insurance') || file.startsWith('skills') || file.startsWith('matching')) return 'admin-login.html';
    if (file.startsWith('farmer')) return 'farmer-login.html';
    if (file.startsWith('worker-foreign')) return 'worker-foreign-login.html';
    if (file.startsWith('worker-local')) return 'worker-local-login.html';
    // 登入大廳在「農業派遣主網站」資料夾；linebot 等其他資料夾的頁面需跨資料夾導向
    let dirPath = global.location.pathname;
    try { dirPath = decodeURIComponent(dirPath); } catch (e) {}
    return dirPath.includes('/農業派遣主網站/') ? 'login.html' : '../農業派遣主網站/login.html';
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

      let sentStoredToken = false;
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
            sentStoredToken = true;
          }
          init = Object.assign({}, init, { headers });
        }
      }

      let response = await nativeFetch(input, init);

      if (requestIsApi && response.status === 401 && !isAuthBootstrapEndpoint(url)) {
        if (MODE === 'production') {
          goToLogin();
        } else if (sentStoredToken && typeof input === 'string') {
          // demo 模式：後端重啟後舊 token 失效（未設定固定密鑰時每次啟動換密鑰），
          // 清掉舊 token、重新 demo-login 後重送一次；仍失敗則交還呼叫端，沿用既有離線 fallback
          clearSession();
          const fresh = await ensureDemoLogin();
          if (fresh) {
            const headers = new Headers(init.headers);
            headers.set('Authorization', `Bearer ${fresh}`);
            response = await nativeFetch(input, Object.assign({}, init, { headers }));
          }
        }
      }

      return response;
    };
  }

  // ------------------------------------------------------------------
  // 角色頁面守門：production 模式才強制導向；demo 模式維持快速展示體驗
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

  // 輕量 Toast（給沒有載入 js/agri-docs.js 的頁面使用，例如 farmers_map.html）
  function toast(title, message, opts = {}) {
    const containerId = 'agriLiteToastContainer';
    let container = document.getElementById(containerId);
    if (!container) {
      container = document.createElement('div');
      container.id = containerId;
      container.className = 'fixed top-5 right-5 z-[99999] flex flex-col gap-2.5 max-w-sm pointer-events-none';
      document.body.appendChild(container);
    }
    const el = document.createElement('div');
    el.className = 'p-4 rounded-2xl shadow-2xl border pointer-events-auto flex items-start gap-3 backdrop-blur-md bg-slate-900/95 border-emerald-500/50 text-slate-100';
    const safeTitle = escapeHtml(title || '');
    const safeMsg = escapeHtml(message || '');
    el.innerHTML = `<div class="text-xl shrink-0">${opts.icon || '🌱'}</div>
      <div class="flex-1 min-w-0"><div class="font-black text-sm text-white">${safeTitle}</div><div class="text-xs text-slate-300 mt-0.5 leading-snug">${safeMsg}</div></div>
      <button class="text-slate-400 hover:text-white text-xs p-1">✕</button>`;
    el.querySelector('button').addEventListener('click', () => el.remove());
    container.appendChild(el);
    setTimeout(() => el.remove(), opts.duration || 4000);
  }

  global.AgriUtil = global.AgriUtil || {};
  global.AgriUtil.escapeHtml = escapeHtml;
  global.AgriUtil.toast = toast;
})(window);
