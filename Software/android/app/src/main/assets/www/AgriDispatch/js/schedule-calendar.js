/* ============================================================
   派工行事曆（工人頁 worker-local／worker-foreign、農場主頁 farmer 共用）
   依訂單工期 startDate～endDate 排在月曆上，點日期看當天明細，下方列出接下來的工作。
   - 工人：ScheduleCalendar.mount(容器, { workerId, lang })，讀 GET /workers/{id}/schedule（本人或 admin），
     後端只回排給這位工人的訂單——有出工名單看名單、沒有名單看所屬工班——且不含金額與農場主電話
     （規則見後端 WorkerScheduleService）；車輛、對口、遇雨改派隨每筆排班回傳。標籤有中／越／印三語。
   - 農場主：ScheduleCalendar.mount(容器, { variant: 'farmer', load })，load 為頁面提供的 async 函式，
     回傳 { items: [訂單…] }（farmer.html 用 GET /orders 的本人訂單，另補 teamName）；只有中文。
     可再給 actions(訂單) 回傳 [{ label, primary, run }]，明細卡底下會出現對應按鈕（例如前往付款、看訂單）。
   換語言 ScheduleCalendar.setLang('vi')。訂單資料（地點、作物）只有中文。
   ============================================================ */
(function () {
  'use strict';

  var API_BASE = window.location.origin.startsWith('http')
    ? window.location.origin + '/api'
    : 'http://localhost:3000/api';

  var TEXT = {
    zh: {
      weekdays: ['日', '一', '二', '三', '四', '五', '六'],
      month: function (y, m) { return y + ' 年 ' + m + ' 月'; },
      today: '今天', prev: '上個月', next: '下個月',
      summary: function (n, d) { return '本月排定 ' + n + ' 件工作・出勤 ' + d + ' 天'; },
      status: { in_progress: '進行中', confirmed: '已排定', pending: '待確認', completed: '已完工' },
      dayTitle: function (m, d, w) { return m + ' 月 ' + d + ' 日（' + w + '）'; },
      dayEmpty: '這天沒有排班，可以休息。',
      upcoming: '接下來的工作',
      upcomingEmpty: '目前沒有已排定的後續工作，後台排班後會出現在這裡並推播 LINE。',
      period: '工期', location: '地點', contact: '現場對口', van: '接送',
      depart: '06:30 發車', departPm: '12:30 發車', content: '作業內容', farmer: '農場主',
      slot: '時段', slots: { morning: '上午 07:00–11:00', afternoon: '下午 13:00–17:00' },
      hedge: function (loc) { return '遇雨改派：' + loc; },
      loading: '讀取排班中…',
      error: '目前讀不到排班資料，請稍後再試或洽帶隊小工頭。',
      noTeam: '你目前尚未編入工班，排班確定後會出現在這裡。',
      pendingNote: '待確認：農場主尚未付定金，工期可能調整。',
      preferred: '農場主指名你（回頭客）'
    },
    vi: {
      weekdays: ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'],
      month: function (y, m) { return 'Tháng ' + m + ' / ' + y; },
      today: 'Hôm nay', prev: 'Tháng trước', next: 'Tháng sau',
      summary: function (n, d) { return 'Tháng này: ' + n + ' công việc・' + d + ' ngày đi làm'; },
      status: { in_progress: 'Đang làm', confirmed: 'Đã xếp lịch', pending: 'Chờ xác nhận', completed: 'Hoàn thành' },
      dayTitle: function (m, d, w) { return d + '/' + m + ' (' + w + ')'; },
      dayEmpty: 'Ngày này không có lịch làm việc, được nghỉ.',
      upcoming: 'Công việc sắp tới',
      upcomingEmpty: 'Chưa có công việc sắp tới. Khi được xếp lịch sẽ hiện ở đây và gửi thông báo LINE.',
      period: 'Thời gian', location: 'Địa điểm', contact: 'Người phụ trách', van: 'Xe đưa đón',
      depart: 'xuất phát 06:30', departPm: 'xuất phát 12:30', content: 'Công việc', farmer: 'Chủ nông trại',
      slot: 'Ca làm', slots: { morning: 'Buổi sáng 07:00–11:00', afternoon: 'Buổi chiều 13:00–17:00' },
      hedge: function (loc) { return 'Nếu mưa chuyển sang: ' + loc; },
      loading: 'Đang tải lịch…',
      error: 'Không tải được lịch làm việc, vui lòng thử lại sau hoặc hỏi trưởng nhóm.',
      noTeam: 'Bạn chưa được xếp vào nhóm, lịch sẽ hiện ở đây khi được xếp.',
      pendingNote: 'Chờ xác nhận: chủ nông trại chưa đặt cọc, lịch có thể thay đổi.',
      preferred: 'Chủ nông trại chỉ định bạn (khách quen)'
    },
    id: {
      weekdays: ['Min', 'Sen', 'Sel', 'Rab', 'Kam', 'Jum', 'Sab'],
      month: function (y, m) { return 'Bulan ' + m + ' / ' + y; },
      today: 'Hari ini', prev: 'Bulan lalu', next: 'Bulan depan',
      summary: function (n, d) { return 'Bulan ini: ' + n + ' pekerjaan・' + d + ' hari kerja'; },
      status: { in_progress: 'Sedang berjalan', confirmed: 'Terjadwal', pending: 'Menunggu', completed: 'Selesai' },
      dayTitle: function (m, d, w) { return d + '/' + m + ' (' + w + ')'; },
      dayEmpty: 'Tidak ada jadwal kerja hari ini, waktunya istirahat.',
      upcoming: 'Pekerjaan berikutnya',
      upcomingEmpty: 'Belum ada pekerjaan terjadwal. Jadwal baru akan muncul di sini dan dikirim lewat LINE.',
      period: 'Periode', location: 'Lokasi', contact: 'Penanggung jawab', van: 'Antar-jemput',
      depart: 'berangkat 06:30', departPm: 'berangkat 12:30', content: 'Pekerjaan', farmer: 'Pemilik kebun',
      slot: 'Sesi', slots: { morning: 'Pagi 07:00–11:00', afternoon: 'Siang 13:00–17:00' },
      hedge: function (loc) { return 'Jika hujan pindah ke: ' + loc; },
      loading: 'Memuat jadwal…',
      error: 'Jadwal tidak dapat dimuat, coba lagi nanti atau hubungi mandor.',
      noTeam: 'Anda belum masuk tim, jadwal akan muncul di sini setelah ditetapkan.',
      pendingNote: 'Menunggu: pemilik kebun belum bayar DP, jadwal bisa berubah.',
      preferred: 'Pemilik kebun memilih Anda (pelanggan tetap)'
    }
  };

  // 農場主版：同一份月曆，用詞改為「派工」角度（待付定金、工班到場），只有中文
  var FARMER_ZH = {
    summary: function (n, d) { return '本月 ' + n + ' 張訂單施工・工班到場 ' + d + ' 天'; },
    status: { in_progress: '施工中', confirmed: '已排定', pending: '待付定金', completed: '已完工' },
    dayEmpty: '這天沒有排工。',
    upcoming: '接下來的派工',
    upcomingEmpty: '目前沒有已排定的後續派工，可到「預約派工」新增。',
    team: '工班', crewSize: '出工人數', teamPending: '工班安排中',
    loading: '讀取派工排程中…',
    error: '目前讀不到訂單資料，請稍後再試。',
    pendingNote: '尚未付定金：付款後才鎖定工班與工期。',
    preferredWorker: '指名工人'
  };

  var PILL = { in_progress: 'ad-pill ad-pill--ok', confirmed: 'ad-pill ad-pill--ok', pending: 'ad-pill ad-pill--warn', completed: 'ad-pill' };

  var STYLE = [
    '.wc-head{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:8px 16px;margin-bottom:14px}',
    '.wc-nav{display:flex;align-items:center;gap:6px}',
    '.wc-title{font-weight:700;font-size:18px;min-width:9em;text-align:center;white-space:nowrap}',
    '.wc-grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:4px}',
    '.wc-wd{text-align:center;font-size:12px;font-weight:600;color:var(--ad-text-2);padding:4px 0}',
    '.wc-day{min-height:84px;padding:4px;border:1px solid var(--ad-border);border-radius:var(--ad-radius-sm);background:var(--ad-surface);',
    'display:flex;flex-direction:column;gap:3px;text-align:left;font:inherit;color:inherit;cursor:pointer;min-width:0}',
    '.wc-day:hover{border-color:var(--ad-border-strong)}',
    '.wc-day:focus-visible{outline:none;box-shadow:var(--ad-ring)}',
    '.wc-day.is-out{opacity:.4}',
    '.wc-day.is-sel{border-color:var(--ad-primary);background:var(--ad-primary-soft)}',
    '.wc-num{font-size:13px;font-weight:600;font-variant-numeric:tabular-nums;line-height:20px;width:22px;height:22px;text-align:center;border-radius:999px}',
    '.wc-day.is-today .wc-num{background:var(--ad-primary);color:var(--ad-on-primary)}',
    '.wc-chip{display:block;font-size:11px;line-height:16px;padding:0 4px;border-radius:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;',
    'background:var(--ad-primary-soft);color:var(--ad-primary-text)}',
    '.wc-chip--pending{background:var(--ad-accent-soft);color:var(--ad-accent-text)}',
    '.wc-chip--completed{background:var(--ad-surface-2);color:var(--ad-text-2)}',
    '.wc-chip--in_progress{background:var(--ad-primary);color:var(--ad-on-primary)}',
    '.wc-more{font-size:11px;color:var(--ad-text-2)}',
    '.wc-legend{display:flex;flex-wrap:wrap;gap:6px 14px;margin-top:10px;font-size:12px;color:var(--ad-text-2)}',
    '.wc-legend span{display:inline-flex;align-items:center;gap:6px}',
    '.wc-legend i{width:12px;height:12px;border-radius:3px;display:inline-block}',
    '.wc-section{margin-top:22px}',
    '.wc-section h4{font-weight:700;margin-bottom:10px}',
    '.wc-detail{display:grid;grid-template-columns:auto 1fr;gap:2px 10px;font-size:13px;margin-top:6px}',
    '.wc-detail dt{color:var(--ad-text-2)}',
    '.wc-detail dd{margin:0;color:var(--ad-text);min-width:0;overflow-wrap:anywhere}',
    '@media (max-width:640px){',
    '.wc-day{min-height:52px;padding:3px 2px;align-items:center}',
    '.wc-chip{width:100%;height:5px;padding:0;font-size:0;line-height:0;background:var(--ad-primary);opacity:.55}',
    '.wc-chip--in_progress{opacity:1}',
    '.wc-chip--pending{background:var(--ad-accent)}',
    '.wc-chip--completed{background:var(--ad-border-strong)}',
    '.wc-more{font-size:10px}',
    '.wc-title{font-size:16px;min-width:0}',
    '}'
  ].join('');

  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function short(v) { return String(v || '').split(' (')[0]; }
  function pad(n) { return String(n).padStart(2, '0'); }
  function ymd(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function parse(s) { var p = String(s).split('-').map(Number); return new Date(p[0], p[1] - 1, p[2]); }

  var state = {
    el: null, lang: 'zh', workerId: null, seq: 0,
    loading: false, error: null, noTeam: false,
    variant: 'worker', loader: null, actions: null, actionMap: {},
    orders: [], team: null,
    view: null, selected: null
  };

  function t() {
    if (state.variant === 'farmer') return Object.assign({}, TEXT.zh, FARMER_ZH);
    return TEXT[state.lang] || TEXT.zh;
  }

  function injectStyle() {
    if (document.getElementById('wc-style')) return;
    var s = document.createElement('style');
    s.id = 'wc-style';
    s.textContent = STYLE;
    document.head.appendChild(s);
  }

  // 某天（YYYY-MM-DD）落在哪些訂單的工期內；沒有 endDate 的訂單只算開工當天
  function ordersOn(day) {
    return state.orders.filter(function (o) {
      return o.startDate <= day && day <= (o.endDate || o.startDate);
    });
  }

  async function load() {
    var seq = ++state.seq;
    state.loading = true; state.error = null; state.noTeam = false;
    render();
    var get = async function (path) {
      var res = await fetch(API_BASE + path);
      if (!res.ok) throw new Error(path);
      return (await res.json()).data;
    };
    try {
      var data = state.loader ? await state.loader() : await get('/workers/' + encodeURIComponent(state.workerId) + '/schedule');
      if (seq !== state.seq) return;
      state.team = (data && data.team) || null;
      // 後端已依開工日排序、排除取消與未排工期的訂單
      state.orders = ((data && data.items) || []).filter(function (i) {
        return i.startDate && i.status !== 'cancelled';
      }).map(function (i) {
        var o = Object.assign({}, i);
        o.id = i.orderId || i.id;
        return o;
      }).sort(function (a, b) {
        var pm = function (o) { return o.timeSlot === 'afternoon' ? 1 : 0; };   // 同一天：上午（含整天）在前、下午在後
        return String(a.startDate).localeCompare(String(b.startDate)) || pm(a) - pm(b) || String(a.id).localeCompare(String(b.id));
      });
      state.noTeam = state.variant === 'worker' && !(data && data.teamId) && !state.orders.length;
    } catch (e) {
      if (seq !== state.seq) return;
      state.orders = []; state.team = null; state.error = true;
    }
    state.loading = false;
    render();
  }

  function orderCard(o, withPeriod) {
    var tx = t(), team = state.team || {};
    var period = o.endDate && o.endDate !== o.startDate ? o.startDate.slice(5) + '～' + o.endDate.slice(5) : o.startDate.slice(5);
    var content = [o.categoryName, o.foremanNotes].filter(Boolean).join('：') || team.normalTask || '';
    var contact = o.foremanName || team.leader || '';
    var phone = o.foremanPhone || team.leaderPhone || '';
    var vehicle = o.vehicle || team.vehicle;
    var van = vehicle ? short(vehicle) + '・' + (o.timeSlot === 'afternoon' ? tx.departPm : tx.depart) : '';
    var rows = [];
    if (withPeriod) rows.push([tx.period, period]);
    if (tx.slots && tx.slots[o.timeSlot]) rows.push([tx.slot, tx.slots[o.timeSlot]]);   // 半日時段（整天不顯示）
    rows.push([tx.location, short(o.farmName ? o.farmName + '・' + (o.farmLocation || '') : o.farmLocation) || short(team.normalLocation)]);
    if (content) rows.push([tx.content, content]);
    if (state.variant === 'farmer') {
      var size = o.requestedWorkers || o.suggestedWorkers;
      rows.push([tx.team, o.teamName || tx.teamPending]);   // 查不到工班名稱（未指派或舊資料的「待分派」）一律顯示安排中
      if (o.assignedTeamId && o.foremanName) rows.push([tx.contact, o.foremanPhone ? o.foremanName + '（' + o.foremanPhone + '）' : o.foremanName]);
      if (size) rows.push([tx.crewSize, size + ' 人／日']);
      if (o.preferredWorkerName) rows.push([tx.preferredWorker, o.preferredWorkerName]);
    } else {
      if (contact) rows.push([tx.contact, phone ? contact + '（' + phone + '）' : contact]);
      if (van) rows.push([tx.van, van]);
      if (o.farmerName) rows.push([tx.farmer, o.farmerName]);
    }
    return '<div class="ad-row" style="align-items:flex-start;' + (o.status === 'in_progress' ? 'border-color:var(--ad-primary);' : '') + '">' +
      '<div class="ad-row__main">' +
      '<div style="display:flex;align-items:flex-start;justify-content:space-between;gap:8px;">' +
      '<div class="ad-row__title" style="white-space:normal;">' + esc(short(o.cropName)) + (o.area ? '（' + esc(o.area) + ' 分地）' : '') + '</div>' +
      '<span class="' + (PILL[o.status] || 'ad-pill') + '" style="flex-shrink:0;">' + esc(tx.status[o.status] || o.status) + '</span>' +
      '</div>' +
      '<div class="ad-row__meta ad-num">' + esc(o.id) + (withPeriod ? '' : '・' + esc(tx.period) + ' ' + esc(period)) + '</div>' +
      '<dl class="wc-detail">' + rows.map(function (r) { return '<dt>' + esc(r[0]) + '</dt><dd>' + esc(r[1]) + '</dd>'; }).join('') + '</dl>' +
      (o.status === 'pending' ? '<div class="ad-caption" style="margin-top:6px;color:var(--ad-accent-text);">' + esc(tx.pendingNote) + '</div>' : '') +
      (o.preferredByFarmer && tx.preferred ? '<div style="margin-top:6px;"><span class="ad-pill ad-pill--warn">' + esc(tx.preferred) + '</span></div>' : '') +
      actionButtons(o) +
      '</div>' +
      '</div>';
  }

  // 頁面提供的明細卡按鈕；處理函式存在 actionMap，點擊時依 data-wc-action 找回
  function actionButtons(o) {
    if (!state.actions) return '';
    var list = state.actions(o) || [];
    if (!list.length) return '';
    return '<div style="display:flex;flex-wrap:wrap;gap:8px;margin-top:10px;">' + list.map(function (a) {
      var key = 'a' + (++actionSeq);
      state.actionMap[key] = a.run;
      return '<button type="button" class="ad-btn ad-btn--sm ' + (a.primary ? 'ad-btn--accent' : 'ad-btn--secondary') + '" data-wc-action="' + key + '">' + esc(a.label) + '</button>';
    }).join('') + '</div>';
  }
  var actionSeq = 0;

  function render() {
    var el = state.el;
    if (!el) return;
    state.actionMap = {};
    var tx = t();
    var today = ymd(new Date());
    var y = state.view.getFullYear(), m = state.view.getMonth();

    var head = '<div class="wc-head">' +
      '<div class="wc-nav">' +
      '<button type="button" class="ad-btn ad-btn--ghost ad-btn--sm ad-btn--icon" data-wc="prev" aria-label="' + esc(tx.prev) + '" title="' + esc(tx.prev) + '">‹</button>' +
      '<span class="wc-title ad-num">' + esc(tx.month(y, m + 1)) + '</span>' +
      '<button type="button" class="ad-btn ad-btn--ghost ad-btn--sm ad-btn--icon" data-wc="next" aria-label="' + esc(tx.next) + '" title="' + esc(tx.next) + '">›</button>' +
      '<button type="button" class="ad-btn ad-btn--secondary ad-btn--sm" data-wc="today">' + esc(tx.today) + '</button>' +
      '</div>';

    if (state.loading) { el.innerHTML = head + '</div><p class="ad-caption">' + esc(tx.loading) + '</p>'; return; }
    if (state.error || state.noTeam) {
      el.innerHTML = head + '</div><div class="ad-notice' + (state.error ? ' ad-notice--warn' : '') + '">' + esc(state.error ? tx.error : tx.noTeam) + '</div>';
      return;
    }

    // 本月統計：排到本月的訂單數、本月有排班的天數
    var first = new Date(y, m, 1), last = new Date(y, m + 1, 0);
    var mFirst = ymd(first), mLast = ymd(last);
    var inMonth = state.orders.filter(function (o) { return o.startDate <= mLast && (o.endDate || o.startDate) >= mFirst; });
    var workDays = 0;
    for (var d = 1; d <= last.getDate(); d++) if (ordersOn(ymd(new Date(y, m, d))).length) workDays++;
    head += '<span class="ad-caption">' + esc(tx.summary(inMonth.length, workDays)) + '</span></div>';

    // 月曆格：從本月第一天所在那週的週日起，排滿 6 週
    var start = new Date(y, m, 1 - first.getDay());
    var cells = tx.weekdays.map(function (w) { return '<div class="wc-wd">' + esc(w) + '</div>'; });
    for (var i = 0; i < 42; i++) {
      var day = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
      var key = ymd(day);
      var list = ordersOn(key);
      var cls = 'wc-day' + (day.getMonth() !== m ? ' is-out' : '') + (key === today ? ' is-today' : '') + (key === state.selected ? ' is-sel' : '');
      var chips = list.slice(0, 2).map(function (o) {
        return '<span class="wc-chip wc-chip--' + esc(o.status) + '">' + esc(short(o.cropName)) + '</span>';
      }).join('') + (list.length > 2 ? '<span class="wc-more">+' + (list.length - 2) + '</span>' : '');
      var label = key + (list.length ? '：' + list.map(function (o) { return short(o.cropName); }).join('、') : '');
      cells.push('<button type="button" class="' + cls + '" data-wc-day="' + key + '" aria-label="' + esc(label) + '"' + (key === state.selected ? ' aria-pressed="true"' : '') + '>' +
        '<span class="wc-num">' + day.getDate() + '</span>' + chips + '</button>');
    }
    var grid = '<div class="wc-grid">' + cells.join('') + '</div>';

    var legend = '<div class="wc-legend">' +
      '<span><i style="background:var(--ad-primary)"></i>' + esc(tx.status.in_progress) + '</span>' +
      '<span><i style="background:var(--ad-primary-soft);border:1px solid var(--ad-primary)"></i>' + esc(tx.status.confirmed) + '</span>' +
      '<span><i style="background:var(--ad-accent-soft);border:1px solid var(--ad-accent)"></i>' + esc(tx.status.pending) + '</span>' +
      '<span><i style="background:var(--ad-surface-2);border:1px solid var(--ad-border-strong)"></i>' + esc(tx.status.completed) + '</span>' +
      '</div>';

    // 選取日的工作明細
    var sel = parse(state.selected);
    var selList = ordersOn(state.selected);
    var dayHtml = '<div class="wc-section"><h4 class="ad-num">' + esc(tx.dayTitle(sel.getMonth() + 1, sel.getDate(), tx.weekdays[sel.getDay()])) + '</h4>' +
      (selList.length
        ? '<div class="ad-list">' + selList.map(function (o) { return orderCard(o, true); }).join('') + '</div>' +
          selList.filter(function (o) { return o.hedgeLocation; }).slice(0, 1).map(function (o) {
            return '<div class="ad-notice ad-notice--warn" style="margin-top:8px;">' + esc(tx.hedge(o.hedgeLocation)) + (o.hedgeTask ? '（' + esc(o.hedgeTask) + '）' : '') + '</div>';
          }).join('')
        : '<p class="ad-caption">' + esc(tx.dayEmpty) + '</p>') +
      '</div>';

    // 接下來的工作：今天之後才開工的訂單（今天進行中的已在上面當日明細）
    var upcoming = state.orders.filter(function (o) { return o.startDate > today && o.status !== 'completed'; }).slice(0, 5);
    var upHtml = '<div class="wc-section"><h4>' + esc(tx.upcoming) + '</h4>' +
      (upcoming.length
        ? '<div class="ad-list">' + upcoming.map(function (o) { return orderCard(o, false); }).join('') + '</div>'
        : '<p class="ad-caption">' + esc(tx.upcomingEmpty) + '</p>') +
      '</div>';

    el.innerHTML = head + grid + legend + dayHtml + upHtml;
  }

  function onClick(e) {
    var act = e.target.closest('[data-wc-action]');
    if (act && state.el.contains(act)) {
      var run = state.actionMap[act.dataset.wcAction];
      if (typeof run === 'function') run();
      return;
    }
    var btn = e.target.closest('[data-wc],[data-wc-day]');
    if (!btn || !state.el.contains(btn)) return;
    var v = state.view;
    if (btn.dataset.wcDay) {
      state.selected = btn.dataset.wcDay;
      var d = parse(state.selected);
      if (d.getMonth() !== v.getMonth() || d.getFullYear() !== v.getFullYear()) state.view = new Date(d.getFullYear(), d.getMonth(), 1);
    } else if (btn.dataset.wc === 'prev') {
      state.view = new Date(v.getFullYear(), v.getMonth() - 1, 1);
    } else if (btn.dataset.wc === 'next') {
      state.view = new Date(v.getFullYear(), v.getMonth() + 1, 1);
    } else if (btn.dataset.wc === 'today') {
      var now = new Date();
      state.view = new Date(now.getFullYear(), now.getMonth(), 1);
      state.selected = ymd(now);
    }
    render();
  }

  window.ScheduleCalendar = {
    // 掛到容器上並讀取排班；工人頁換工人（例如切換示範身分）、農場主頁切回頁籤時再呼叫一次即可
    mount: function (el, opts) {
      if (!el) return;
      opts = opts || {};
      injectStyle();
      if (state.el !== el) {
        if (state.el) state.el.removeEventListener('click', onClick);
        state.el = el;
        el.addEventListener('click', onClick);
      }
      if (opts.lang) state.lang = TEXT[opts.lang] ? opts.lang : 'zh';
      var now = new Date();
      if (!state.view) state.view = new Date(now.getFullYear(), now.getMonth(), 1);
      if (!state.selected) state.selected = ymd(now);
      if (typeof opts.load === 'function') {
        // 頁面提供資料（農場主）：每次掛載都重讀，訂單剛下單或付款後切過來就是最新的
        state.variant = opts.variant || 'farmer';
        state.loader = opts.load;
        state.actions = typeof opts.actions === 'function' ? opts.actions : null;
        load();
      } else if (opts.workerId && opts.workerId !== state.workerId) {
        state.workerId = opts.workerId;
        load();
      } else {
        render();
      }
    },
    setLang: function (lang) {
      state.lang = TEXT[lang] ? lang : 'zh';
      if (state.view) render();
    }
  };
})();
