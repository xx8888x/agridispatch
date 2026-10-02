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

  window.AgriTheme = { mode: mode, icons: icons, drawer: drawer, toast: toast };

  document.addEventListener('DOMContentLoaded', function () { icons(); });
})();
