/**
 * 智農派工 (AgriDispatch) - 設計系統共用設定 (agri-theme.js)
 *
 * 1. 統一 tailwind.config：各頁原本各自 inline 一份、色階不一致，改為共用這份。
 *    載入順序：app-config.js → tailwind vendor → agri-theme.js → css/agri.css。
 * 2. 依 AGRI_CONFIG.mode 在 <html> 加上 is-demo / is-production，
 *    讓 [data-demo-only] 元素（示範導覽抽屜、免密提示）只在 demo 出現。
 * 3. 提供 AgriTheme.icons()：頁面載入 lucide 後呼叫，把 <i data-lucide> 換成 SVG。
 *
 * 三個資料夾（AgriDispatch／TechBackend／linebot）各一份，內容必須相同。
 * 規範：07_系統分析及文件/04_UI設計系統規範.md
 */
(function () {
  'use strict';

  var PRIMARY = {
    50: '#ecfdf5', 100: '#d1fae5', 200: '#a7f3d0', 300: '#6ee7b7', 400: '#34d399',
    500: '#10b981', 600: '#059669', 700: '#047857', 800: '#065f46', 900: '#064e3b', 950: '#022c22'
  };
  var ACCENT = {
    50: '#fffbeb', 100: '#fef3c7', 300: '#fcd34d', 400: '#fbbf24',
    500: '#f59e0b', 600: '#d97706', 700: '#b45309'
  };

  // 1. Tailwind 共用設定（CDN 版會在 DOMContentLoaded 前讀取 window.tailwind.config）
  if (typeof window.tailwind !== 'undefined') {
    window.tailwind.config = {
      theme: {
        extend: {
          colors: {
            brand: PRIMARY,
            accent: ACCENT,
            line: { DEFAULT: '#06c755', hover: '#05b34c' }
          },
          fontFamily: {
            sans: ['Noto Sans TC', 'Plus Jakarta Sans', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif']
          },
          borderRadius: { ad: '12px' },
          maxWidth: { ad: '1200px' }
        }
      }
    };
  }

  // 2. 模式標記
  var mode = (window.AGRI_CONFIG && window.AGRI_CONFIG.mode) || 'production';
  var root = document.documentElement;
  root.classList.add(mode === 'production' ? 'is-production' : 'is-demo');

  // 2b. 功能開關（AGRI_CONFIG.features）：關閉的功能，標了 data-feature="名稱" 的元素一律隱藏
  var FEATURES = (window.AGRI_CONFIG && window.AGRI_CONFIG.features) || {};
  function feature(name) { return FEATURES[name] !== false; }
  (function () {
    // data-feature-off="名稱"：只在該功能關閉時顯示（例如下架後的替代文字）
    var off = Object.keys(FEATURES).filter(function (k) { return FEATURES[k] === false; });
    var css = document.createElement('style');
    css.textContent = '[data-feature-off]{display:none !important;}' +
      (off.length ? off.map(function (k) { return '[data-feature="' + k + '"]'; }).join(',') + '{display:none !important;}' +
        off.map(function (k) { return '[data-feature-off="' + k + '"]'; }).join(',') + '{display:inline !important;}' : '');
    (document.head || root).appendChild(css);
  })();

  // 2c. 本國工人工作型態（後端 WorkerService.EMPLOYMENT_TYPES 同一份代碼）
  var EMPLOYMENT = { full_time: '正職', part_time: '兼職', senior: '中高齡', casual: '臨時打工', student: '學生打工' };
  function employmentLabel(code) { return EMPLOYMENT[code] || ''; }
  // features.foreignWorkers 關閉時，名冊、工班成員等清單不列外籍移工（tier1_foreign）；資料仍在後端
  function visibleWorkers(list) {
    if (feature('foreignWorkers') || !Array.isArray(list)) return list;
    return list.filter(function (w) { return !w || w.tier !== 'tier1_foreign'; });
  }

  // 3. 圖示初始化（lucide 可能晚於本檔載入，所以提供函式而非直接執行）
  function icons(scope) {
    if (window.lucide && typeof window.lucide.createIcons === 'function') {
      window.lucide.createIcons(scope ? { nameAttr: 'data-lucide', attrs: {}, icons: undefined, root: scope } : undefined);
    }
  }

  // 4. 抽屜（demo 導覽）
  function drawer(id) {
    var el = document.getElementById(id);
    if (!el) return null;
    var backdrop = el.previousElementSibling && el.previousElementSibling.classList.contains('ad-drawer__backdrop')
      ? el.previousElementSibling : null;
    function set(open) {
      el.classList.toggle('is-open', open);
      if (backdrop) backdrop.classList.toggle('is-open', open);
      el.setAttribute('aria-hidden', open ? 'false' : 'true');
    }
    if (backdrop) backdrop.addEventListener('click', function () { set(false); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') set(false); });
    return { open: function () { set(true); }, close: function () { set(false); }, toggle: function () { set(!el.classList.contains('is-open')); } };
  }

  // 5. Toast：全站統一的短訊息
  var toastTimer = null;
  function toast(message, ms) {
    var el = document.getElementById('ad-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'ad-toast';
      el.className = 'ad-toast';
      el.setAttribute('role', 'status');
      el.hidden = true;
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, ms || 2400);
  }

  // 6. 示範導覽抽屜：全站共用一份頁面清單，由各頁呼叫 AgriTheme.demoNav() 注入。
  //    paths 依呼叫頁所在資料夾給相對前綴；預設為「AgriDispatch/」內的頁面。
  //    production 模式不注入任何 DOM。
  var DEMO_PAGES = [
    { group: '角色工作台', items: [
      { root: 'main', href: 'farmer.html', title: '農場主', meta: '預約、訂單、工班' },
      { root: 'main', href: 'worker-foreign.html', title: '外展移工', meta: '今日任務、打卡、薪資（越／印／中）', feature: 'foreignWorkers' },
      { root: 'main', href: 'worker-local.html', title: '帶隊領班／機耕師', meta: '帶隊、打卡、薪資' },
      { root: 'main', href: 'worker-local.html?role=labor', title: '合作社勞務員', meta: '出勤、打卡、薪資' },
      { root: 'tech', href: 'admin.html', title: '科技營運後台', meta: '天候戰情、派工調度、營收' }
    ] },
    { group: '營運工具', items: [
      { root: 'tech', href: 'insurance.html', title: '保險登錄與加保檢查', meta: '勞健保、職災險' },
      { root: 'tech', href: 'skills.html', title: '技能標籤與複核', meta: '熟手判定與審核' },
      { root: 'tech', href: 'matching.html', title: '派工媒合與出工名單', meta: '撞期排除、人選建議' },
      { root: 'tech', href: 'admin-line.html', title: 'LINE 官方帳號', meta: '兩帳號狀態、帳號綁定' },
      { root: 'tech', href: 'accounts.html', title: '帳號與密碼管理', meta: '忘記密碼時重設臨時密碼' },
      { root: 'tech', href: 'leaves.html', title: '請假審核', meta: '工人請假核准、撞期改派' },
      { root: 'tech', href: 'referrals.html', title: '推薦紀錄', meta: '邀請連結帶來的農場主、工人意願登記跟進' },
      { root: 'tech', href: 'payroll.html', title: '出勤補登與月結算', meta: '漏打卡補登、薪資結算鎖定' }
    ] },
    { group: '介紹與工具', items: [
      { root: 'main', href: 'index.html', title: '首頁', meta: '農民入口' },
      { root: 'main', href: 'pitch.html', title: '產品介紹', meta: '痛點、雙軌架構、52 週產曆' },
      { root: 'main', href: 'brochure.html', title: '文宣 DM', meta: '四大工時包，A4 可列印' },
      { root: 'main', href: 'farmers_map.html', title: '產地地圖', meta: '全台農會名冊與產季' },
      { root: 'main', href: 'coldchain.html', title: '冷鏈與物流', meta: '雨天改派與預冷場' },
      { root: 'main', href: 'legalize.html', title: '法規與合規', meta: '外展農務制度與雙軌架構', feature: 'legalize' },
      { root: 'line', href: 'line.html', title: 'LINE Bot', meta: '圖文選單與對話模擬' },
      { root: 'main', href: 'invite.html', title: '邀請夥伴', meta: '推廣連結：Facebook、LINE 邀請農場主與工人' },
      { root: 'main', href: 'login.html', title: '登入大廳', meta: '農場主、本國工人入口' }
    ] }
  ];

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // 依目前頁面所在資料夾推算三個資料夾的相對前綴（呼叫端沒給 paths 時用）
  function defaultPaths() {
    var dir = window.location.pathname;
    try { dir = decodeURIComponent(dir); } catch (e) {}
    if (dir.indexOf('/TechBackend/') >= 0) return { main: '../AgriDispatch/', tech: '', line: '../linebot/' };
    if (dir.indexOf('/linebot/') >= 0) return { main: '../AgriDispatch/', tech: '../TechBackend/', line: '' };
    return { main: '', tech: '../TechBackend/', line: '../linebot/' };
  }

  function demoNav(paths) {
    var noop = { open: function () {}, close: function () {}, toggle: function () {} };
    if (mode === 'production') return noop;
    var p = paths || defaultPaths();
    var id = 'ad-demo-drawer';
    if (document.getElementById(id)) return drawer(id) || noop;

    var html = '<div class="ad-card__head"><h2 class="ad-title">示範導覽</h2>' +
      '<button type="button" class="ad-btn ad-btn--ghost ad-btn--icon ad-btn--sm" data-ad-close aria-label="關閉"><i data-lucide="x" class="ad-icon"></i></button></div>' +
      '<p class="ad-caption" style="margin-bottom:16px">路演用。正式版不會顯示這個選單。</p>';
    DEMO_PAGES.forEach(function (g, gi) {
      html += '<div class="ad-caption" style="font-weight:600;margin-bottom:8px' + (gi ? ';margin-top:20px' : '') + '">' + escapeHtml(g.group) + '</div><div class="ad-list">';
      g.items.filter(function (it) { return !it.feature || feature(it.feature); }).forEach(function (it) {
        html += '<a href="' + escapeHtml((p[it.root] || '') + it.href) + '" class="ad-row"><div class="ad-row__main">' +
          '<div class="ad-row__title">' + escapeHtml(it.title) + '</div><div class="ad-row__meta">' + escapeHtml(it.meta) + '</div></div>' +
          '<i data-lucide="chevron-right" class="ad-icon ad-icon--sm ad-text-3"></i></a>';
      });
      html += '</div>';
    });
    if (window.AgriMock) {
      html += '<div class="ad-caption" style="margin-top:20px">純前端示範：資料存在這台電腦的瀏覽器，路演前可重置回初始狀態。</div>' +
        '<button type="button" class="ad-btn ad-btn--secondary ad-btn--sm" style="margin-top:8px" data-ad-mock-reset>' +
        '<i data-lucide="rotate-ccw" class="ad-icon ad-icon--sm"></i>重置示範資料</button>';
    }

    var backdrop = document.createElement('div');
    backdrop.className = 'ad-drawer__backdrop';
    var aside = document.createElement('aside');
    aside.id = id;
    aside.className = 'ad-drawer';
    aside.setAttribute('aria-hidden', 'true');
    aside.setAttribute('aria-label', '示範導覽');
    aside.setAttribute('data-demo-only', '');
    aside.innerHTML = html;
    document.body.appendChild(backdrop);
    document.body.appendChild(aside);

    var ctl = drawer(id) || noop;
    aside.querySelector('[data-ad-close]').addEventListener('click', ctl.close);
    var resetBtn = aside.querySelector('[data-ad-mock-reset]');
    if (resetBtn) {
      resetBtn.addEventListener('click', function () {
        if (!window.confirm('把訂單、保險、天候情境等示範資料恢復成初始狀態？')) return;
        window.AgriMock.reset();
        try { localStorage.removeItem('agri_orders'); } catch (e) {}
        window.location.reload();
      });
    }
    icons(aside);
    return ctl;
  }

  // 頁首沒有「示範導覽」按鈕的頁面（登入頁、DM、證明書等），demo 模式補一顆浮動按鈕，路演時任何頁都能跳轉
  function demoNavFab() {
    if (mode === 'production' || document.getElementById('ad-demo-fab')) return;
    // 頁首已有示範導覽按鈕（onclick 或 Vue @click，後者掛載後屬性會消失，所以也認 aria-label）
    if (document.querySelector('[data-demo-only][onclick*=".open()"], button[data-demo-only][aria-label="示範導覽"]')) return;
    var ctl = demoNav();
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.id = 'ad-demo-fab';
    btn.className = 'ad-btn ad-btn--secondary ad-btn--sm';
    btn.setAttribute('data-demo-only', '');
    btn.setAttribute('aria-label', '示範導覽');
    btn.style.cssText = 'position:fixed;left:16px;bottom:16px;z-index:60;box-shadow:0 4px 12px rgba(0,0,0,.15)';
    btn.innerHTML = '<i data-lucide="layout-grid" class="ad-icon ad-icon--sm"></i><span class="ad-hide-sm">示範導覽</span>';
    btn.addEventListener('click', ctl.open);
    var css = document.createElement('style');
    css.textContent = '@media print { #ad-demo-fab { display: none !important; } }';
    document.head.appendChild(css);
    document.body.appendChild(btn);
    icons(btn);
  }

  // 7. 帳號選單：點頁首頭像（aria-label="切換帳號" 或 data-account-menu）展開，提供我的帳號、修改密碼、登出。
  //    用事件委派攔截點擊、選單掛在 body，不改動頁首 DOM（admin.html 等頁首由 Vue 管理）。
  //    已登入但頁首沒有頭像的頁面，在 .ad-header__actions 補一顆。
  // 選單文字依頁面語言（<html lang>，外勞頁 setLang 會更新）；「我的帳號」頁本身目前只有中文
  var AM_TEXT = {
    zh: { roles: { farmer: '農場主', worker_local: '本國工人', worker_foreign: '外籍移工', admin_tech: '科技後台管理員', coop_admin: '合作社管理員' },
      account: '帳號 ', invite: '邀請夥伴', mine: '我的帳號', password: '修改密碼', manage: '帳號與密碼管理', switchAcct: '切換帳號',
      logout: '登出', guest: '尚未登入', login: '登入', menu: '帳號選單' },
    vi: { roles: { farmer: 'Chủ nông trại', worker_local: 'Lao động Đài Loan', worker_foreign: 'Lao động nước ngoài', admin_tech: 'Quản trị viên', coop_admin: 'Quản trị hợp tác xã' },
      account: 'Tài khoản ', invite: 'Mời bạn bè', mine: 'Tài khoản của tôi', password: 'Đổi mật khẩu', manage: 'Quản lý tài khoản', switchAcct: 'Đổi tài khoản',
      logout: 'Đăng xuất', guest: 'Chưa đăng nhập', login: 'Đăng nhập', menu: 'Menu tài khoản' },
    id: { roles: { farmer: 'Pemilik kebun', worker_local: 'Pekerja Taiwan', worker_foreign: 'Pekerja migran', admin_tech: 'Administrator', coop_admin: 'Admin koperasi' },
      account: 'Akun ', invite: 'Undang teman', mine: 'Akun saya', password: 'Ganti sandi', manage: 'Kelola akun', switchAcct: 'Ganti akun',
      logout: 'Keluar', guest: 'Belum masuk', login: 'Masuk', menu: 'Menu akun' }
  };
  function amText() {
    var l = String(document.documentElement.lang || 'zh').slice(0, 2).toLowerCase();
    return AM_TEXT[l] || AM_TEXT.zh;
  }
  var ROLE_LOGIN = { farmer: ['main', 'farmer-login.html'], worker_local: ['main', 'worker-local-login.html'],
    worker_foreign: ['main', 'worker-foreign-login.html'], admin_tech: ['tech', 'admin-login.html'], coop_admin: ['tech', 'admin-login.html'] };

  function currentUser() {
    try { return window.AgriAuth && window.AgriAuth.getToken() ? window.AgriAuth.getUser() : null; } catch (e) { return null; }
  }

  function loginHref(user, p) {
    var l = user && ROLE_LOGIN[user.role];
    return l ? (p[l[0]] || '') + l[1] : p.main + 'login.html';
  }

  function logout() {
    var href = loginHref(currentUser(), defaultPaths());
    if (window.AgriAuth) window.AgriAuth.clearSession();
    window.location.href = href;
  }

  function closeAccountMenu() {
    var m = document.getElementById('ad-account-menu');
    if (m) m.remove();
    document.removeEventListener('keydown', accountMenuKey);
  }

  function accountMenuKey(e) { if (e.key === 'Escape') closeAccountMenu(); }

  function openAccountMenu(anchor) {
    if (!document.getElementById('ad-account-menu-css')) {
      var css = document.createElement('style');
      css.id = 'ad-account-menu-css';
      css.textContent = '#ad-account-menu{position:fixed;z-index:80;width:260px;max-width:calc(100vw - 32px);background:var(--ad-surface);' +
        'border:1px solid var(--ad-border);border-radius:var(--ad-radius);box-shadow:var(--ad-shadow);padding:8px;color:var(--ad-text)}' +
        '#ad-account-menu .am-head{padding:8px 10px 10px;border-bottom:1px solid var(--ad-border);margin-bottom:6px}' +
        '#ad-account-menu .am-name{font-weight:600;overflow-wrap:anywhere}' +
        '#ad-account-menu a,#ad-account-menu button{display:flex;align-items:center;gap:8px;width:100%;padding:9px 10px;border:0;background:none;' +
        'border-radius:var(--ad-radius-sm);color:inherit;font:inherit;text-align:left;text-decoration:none;cursor:pointer}' +
        '#ad-account-menu a:hover,#ad-account-menu button:hover,#ad-account-menu a:focus-visible,#ad-account-menu button:focus-visible{background:var(--ad-surface-2)}' +
        '#ad-account-menu .am-danger{color:var(--ad-danger-text)}' +
        '@media print{#ad-account-menu{display:none!important}}';
      document.head.appendChild(css);
    }
    var p = defaultPaths();
    var user = currentUser();
    var switchHref = anchor.tagName === 'A' && anchor.getAttribute('href') ? anchor.getAttribute('href') : loginHref(user, p);
    var T = amText();
    var html;
    if (user) {
      html = '<div class="am-head"><div class="am-name">' + escapeHtml(user.name || user.username || '') + '</div>' +
        '<div class="ad-caption">' + escapeHtml(T.roles[user.role] || user.role || '') + '・' + escapeHtml(T.account + (user.username || '')) + '</div></div>' +
        '<a role="menuitem" href="' + escapeHtml(p.main + 'account.html') + '"><i data-lucide="user-round" class="ad-icon ad-icon--sm"></i>' + escapeHtml(T.mine) + '</a>' +
        '<a role="menuitem" href="' + escapeHtml(p.main + 'account.html#password') + '"><i data-lucide="key-round" class="ad-icon ad-icon--sm"></i>' + escapeHtml(T.password) + '</a>' +
        ((user.role === 'farmer' || user.role === 'worker_local' || user.role === 'worker_foreign') ? '<a role="menuitem" href="' + escapeHtml(p.main + 'invite.html') + '"><i data-lucide="user-plus" class="ad-icon ad-icon--sm"></i>' + escapeHtml(T.invite) + '</a>' : '') +
        ((user.role === 'admin_tech' || user.role === 'coop_admin') ? '<a role="menuitem" href="' + escapeHtml(p.tech + 'accounts.html') + '"><i data-lucide="users" class="ad-icon ad-icon--sm"></i>' + escapeHtml(T.manage) + '</a>' : '') +
        '<a role="menuitem" href="' + escapeHtml(switchHref) + '"><i data-lucide="repeat" class="ad-icon ad-icon--sm"></i>' + escapeHtml(T.switchAcct) + '</a>' +
        '<button type="button" role="menuitem" class="am-danger" data-am-logout><i data-lucide="log-out" class="ad-icon ad-icon--sm"></i>' + escapeHtml(T.logout) + '</button>';
    } else {
      html = '<div class="am-head"><div class="am-name">' + escapeHtml(T.guest) + '</div></div>' +
        '<a role="menuitem" href="' + escapeHtml(switchHref) + '"><i data-lucide="log-in" class="ad-icon ad-icon--sm"></i>' + escapeHtml(T.login) + '</a>';
    }
    var menu = document.createElement('div');
    menu.id = 'ad-account-menu';
    menu.setAttribute('role', 'menu');
    menu.setAttribute('aria-label', T.menu);
    menu.innerHTML = html;
    document.body.appendChild(menu);
    var r = anchor.getBoundingClientRect();
    menu.style.top = Math.round(r.bottom + 8) + 'px';
    menu.style.right = Math.max(16, Math.round(window.innerWidth - r.right)) + 'px';
    var out = menu.querySelector('[data-am-logout]');
    if (out) out.addEventListener('click', logout);
    icons(menu);
    document.addEventListener('keydown', accountMenuKey);
    var first = menu.querySelector('a,button');
    if (first) first.focus();
  }

  document.addEventListener('click', function (e) {
    var t = e.target && e.target.closest ? e.target : null;
    if (!t) return;
    var anchor = t.closest('[data-account-menu], a[aria-label="切換帳號"]');
    var open = document.getElementById('ad-account-menu');
    if (anchor) {
      e.preventDefault();
      e.stopPropagation();
      if (open) closeAccountMenu(); else openAccountMenu(anchor);
      return;
    }
    if (open && !t.closest('#ad-account-menu')) closeAccountMenu();
  }, true);

  // 合作社帳號：後台頁面的「回後台」「首頁」連結改指向合作社首頁（coop.html）
  function coopHomeLinks() {
    var u = currentUser();
    if (!u || u.role !== 'coop_admin') return;
    document.querySelectorAll('a[href="admin.html"]').forEach(function (a) {
      if (/回後台|^\s*$/.test(a.textContent) || a.classList.contains('ad-header__brand') || a.classList.contains('ad-tabbar__item')) a.setAttribute('href', 'coop.html');
    });
  }

  function accountButton(retried) {
    if (document.querySelector('[data-account-menu], a[aria-label="切換帳號"]')) return;
    var user = currentUser();
    var actions = document.querySelector('.ad-header__actions');
    if (!actions) return;
    if (!user) {
      // demo 模式在頁面第一次呼叫 API 時才自動登入，稍後再看一次
      if (!retried) setTimeout(function () { accountButton(true); }, 1500);
      return;
    }
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'ad-btn ad-btn--ghost ad-btn--sm ad-btn--icon';
    btn.setAttribute('data-account-menu', '');
    btn.setAttribute('aria-label', '帳號選單');
    btn.title = '帳號選單';
    btn.innerHTML = '<span class="ad-avatar">' + escapeHtml(String(user.name || user.username || '我').trim().charAt(0) || '我') + '</span>';
    actions.appendChild(btn);
  }

  // 8. 田間模式：前台（AgriDispatch/）的農場主、本國工人、登入、首頁等頁加上 html.is-field（css/agri.css 第 16 節）。
  //    科技後台、LINE 體驗頁，以及產品介紹、DM、產地地圖、證明書等行銷／列印頁（有寫死的深色樣式）不套。
  //    主題：手機／觸控裝置預設亮色（戶外日光下深色底看不清），桌機維持深色；頁首「切換亮色／深色」鈕，選擇記在 localStorage.agri_theme。
  var FIELD_EXCLUDE = /^(pitch|brochure|farmers_map|farmer-certificate|legalize|coldchain|app)\.html$/;
  var pageFile = (function () {
    var p = window.location.pathname;
    try { p = decodeURIComponent(p); } catch (e) {}
    return (p.split('/').pop() || 'index.html').toLowerCase();
  })();
  var isField = defaultPaths().main === '' && !FIELD_EXCLUDE.test(pageFile);
  var theme = 'dark';

  function applyTheme(t) {
    theme = t;
    if (t === 'light') root.setAttribute('data-theme', 'light'); else root.removeAttribute('data-theme');
    // 手機瀏覽器的網址列顏色跟著表頭
    var meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) { meta = document.createElement('meta'); meta.setAttribute('name', 'theme-color'); (document.head || root).appendChild(meta); }
    meta.setAttribute('content', t === 'light' ? '#ffffff' : '#0f172a');
  }
  function setTheme(t) {
    try { localStorage.setItem('agri_theme', t); } catch (e) { /* 無痕模式等 */ }
    applyTheme(t);
    updateThemeButton();
  }
  function toggleTheme() { setTheme(theme === 'light' ? 'dark' : 'light'); }
  if (isField) {
    root.classList.add('is-field');
    var saved = null;
    try { saved = localStorage.getItem('agri_theme'); } catch (e) {}
    if (saved === 'light' || saved === 'dark') applyTheme(saved);
    else if (root.getAttribute('data-theme') === 'light') applyTheme('light');
    else if (window.matchMedia && (window.matchMedia('(pointer: coarse)').matches || window.matchMedia('(max-width: 767px)').matches)) applyTheme('light');
    else applyTheme('dark');
  }
  function updateThemeButton() {
    var btn = document.getElementById('ad-theme-toggle');
    if (!btn) return;
    var label = theme === 'light' ? '切換深色' : '切換亮色';
    btn.setAttribute('aria-label', label);
    btn.title = label;
    btn.innerHTML = '<i data-lucide="' + (theme === 'light' ? 'moon' : 'sun') + '" class="ad-icon"></i>';
    icons(btn);
  }
  // 頁首右側補一顆亮／暗切換鈕（有的頁首由 Vue 掛載後才出現，所以稍後再試一次）
  function themeButton(retried) {
    if (!isField || document.getElementById('ad-theme-toggle')) return;
    var actions = document.querySelector('.ad-header__actions');
    if (!actions) { if (!retried) setTimeout(function () { themeButton(true); }, 1500); return; }
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.id = 'ad-theme-toggle';
    btn.className = 'ad-btn ad-btn--ghost ad-btn--sm ad-btn--icon';
    btn.addEventListener('click', toggleTheme);
    actions.insertBefore(btn, actions.firstChild);
    updateThemeButton();
  }

  window.AgriTheme = { mode: mode, icons: icons, drawer: drawer, toast: toast, demoNav: demoNav, feature: feature,
    employment: EMPLOYMENT, employmentLabel: employmentLabel, visibleWorkers: visibleWorkers, logout: logout,
    isField: isField, theme: function () { return theme; }, setTheme: setTheme, toggleTheme: toggleTheme };

  document.addEventListener('DOMContentLoaded', function () { icons(); themeButton(); demoNavFab(); accountButton(); coopHomeLinks(); });
})();
