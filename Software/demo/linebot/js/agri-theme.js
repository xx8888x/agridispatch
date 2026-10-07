/**
 * 智農派工 (AgriDispatch) - 設計系統共用設定 (agri-theme.js)
 *
 * 1. 統一 tailwind.config：各頁原本各自 inline 一份、色階不一致，改為共用這份。
 *    載入順序：app-config.js → tailwind vendor → agri-theme.js → css/agri.css。
 * 2. 依 AGRI_CONFIG.mode 在 <html> 加上 is-demo / is-production，
 *    讓 [data-demo-only] 元素（示範導覽抽屜、免密提示）只在 demo 出現。
 * 3. 提供 AgriTheme.icons()：頁面載入 lucide 後呼叫，把 <i data-lucide> 換成 SVG。
 *
 * 三個資料夾（農業派遣主網站／科技後台／linebot）各一份，內容必須相同。
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
  //    paths 依呼叫頁所在資料夾給相對前綴；預設為「農業派遣主網站/」內的頁面。
  //    production 模式不注入任何 DOM。
  var DEMO_PAGES = [
    { group: '角色工作台', items: [
      { root: 'main', href: 'farmer.html', title: '農場主', meta: '預約、訂單、工班' },
      { root: 'main', href: 'worker-foreign.html', title: '外展移工', meta: '今日任務、打卡、薪資（越／印／泰／中）' },
      { root: 'main', href: 'worker-local.html', title: '帶隊領班／機耕師', meta: '帶隊、打卡、薪資' },
      { root: 'main', href: 'worker-local.html?role=labor', title: '合作社勞務員', meta: '出勤、打卡、薪資' },
      { root: 'tech', href: 'admin.html', title: '科技營運後台', meta: '天候戰情、派工調度、營收' },
      { root: 'tech', href: 'vue-crud.html', title: '訂單總台', meta: '訂單新增、編輯、狀態追蹤' }
    ] },
    { group: '營運工具', items: [
      { root: 'tech', href: 'insurance.html', title: '保險登錄與加保檢查', meta: '勞健保、職災險' },
      { root: 'tech', href: 'skills.html', title: '技能標籤與複核', meta: '熟手判定與審核' },
      { root: 'tech', href: 'matching.html', title: '派工媒合與出工名單', meta: '撞期排除、人選建議' },
      { root: 'tech', href: 'admin-line.html', title: 'LINE 官方帳號', meta: '兩帳號狀態、帳號綁定' }
    ] },
    { group: '介紹與工具', items: [
      { root: 'main', href: 'index.html', title: '首頁', meta: '農民入口' },
      { root: 'main', href: 'pitch.html', title: '產品介紹', meta: '痛點、雙軌架構、52 週產曆' },
      { root: 'main', href: 'brochure.html', title: '文宣 DM', meta: '四大工時包，A4 可列印' },
      { root: 'main', href: 'farmers_map.html', title: '產地地圖', meta: '全台農會名冊與產季' },
      { root: 'main', href: 'coldchain.html', title: '冷鏈與物流', meta: '雨天改派與預冷場' },
      { root: 'main', href: 'legalize.html', title: '法規與合規', meta: '外展農務制度與雙軌架構', feature: 'legalize' },
      { root: 'line', href: 'line.html', title: 'LINE Bot', meta: '圖文選單與對話模擬' },
      { root: 'main', href: 'login.html', title: '登入大廳', meta: '五種身分入口' }
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
    if (dir.indexOf('/科技後台/') >= 0) return { main: '../農業派遣主網站/', tech: '', line: '../linebot/' };
    if (dir.indexOf('/linebot/') >= 0) return { main: '../農業派遣主網站/', tech: '../科技後台/', line: '' };
    return { main: '', tech: '../科技後台/', line: '../linebot/' };
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
    btn.innerHTML = '<i data-lucide="layout-grid" class="ad-icon ad-icon--sm"></i>示範導覽';
    btn.addEventListener('click', ctl.open);
    var css = document.createElement('style');
    css.textContent = '@media print { #ad-demo-fab { display: none !important; } }';
    document.head.appendChild(css);
    document.body.appendChild(btn);
    icons(btn);
  }

  window.AgriTheme = { mode: mode, icons: icons, drawer: drawer, toast: toast, demoNav: demoNav, feature: feature };

  document.addEventListener('DOMContentLoaded', function () { icons(); demoNavFab(); });
})();
