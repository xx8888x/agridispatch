/* ============================================================
   出勤補登申請卡（工人頁 worker-local／worker-foreign 共用）
   漏打卡或忘記下班時，工人自己送申請，合作社在科技後台「出勤與月結算」審核；核准才寫入出勤與薪資。
   - AttendanceRequest.mount(容器, { workerId, lang })：workerId 可為字串或回傳字串的函式
   - AttendanceRequest.setLang('vi')：換語言；AttendanceRequest.reload()：重讀近期出勤與申請紀錄
   API：GET /attendance/history/{id}（近期出勤）、GET／POST /workers/{id}/attendance-requests、
        POST /attendance-requests/{id}/cancel（規則見後端 AttendanceRequestService）
   錯誤訊息的細節來自後端（只有中文），常見狀況（403、離線）用翻譯。標籤有中／越／印三語。
   ============================================================ */
(function () {
  'use strict';

  var API_BASE = window.location.origin.startsWith('http')
    ? window.location.origin + '/api'
    : 'http://localhost:3000/api';
  var BACKDATE_DAYS = 30;

  var TEXT = {
    zh: {
      title: '出勤補登申請',
      hint: '漏打卡或忘記下班？送出後由合作社確認，核准才會補進出勤與薪資。',
      recentTitle: '近期出勤（點「申請修正」可補下班或改時間）',
      recentEmpty: '最近沒有出勤紀錄。',
      fix: '申請修正', noOut: '未下班',
      newTitle: '補登漏打卡的一天',
      fixing: '修正 {d} 的出勤',
      fixCancel: '改為補登新的一天',
      dateLbl: '出勤日期', inLbl: '上班時間', outLbl: '下班時間',
      overnight: '下班早於上班時間視為隔天下班。',
      reasonLbl: '原因（必填）', reasonPh: '例：手機沒電，領班可作證',
      submit: '送出申請', sending: '送出中…',
      note: '只能申請 30 天內的出勤；合作社審核前不會改動出勤與薪資。',
      listTitle: '我的申請紀錄', empty: '還沒有申請紀錄。', loading: '讀取中…',
      loadErr: '無法讀取：{msg}',
      sent: '已送出（{id}），等合作社審核。', fail: '未送出：{msg}',
      cancel: '取消', confirmCancel: '確定取消這筆申請？', cancelFail: '取消失敗：{msg}',
      st_pending: '待審核', st_approved: '已核准', st_rejected: '已駁回', st_cancelled: '已取消',
      kind_create: '補登', kind_update: '修正',
      rejectNote: '駁回原因：{n}', decisionNote: '合作社備註：{n}',
      errTime: '請填上班與下班時間。', errDate: '請選擇出勤日期。', errReason: '請填寫原因。',
      errForbidden: '沒有權限處理這位工人的申請', errOffline: '無法連線伺服器，請稍後再試'
    },
    vi: {
      title: 'Xin bổ sung chấm công',
      hint: 'Quên chấm công hoặc quên chấm giờ về? Gửi yêu cầu, hợp tác xã xác nhận xong mới được ghi vào chấm công và lương.',
      recentTitle: 'Chấm công gần đây (bấm "Xin sửa" để bổ sung giờ về hoặc sửa giờ)',
      recentEmpty: 'Gần đây chưa có chấm công.',
      fix: 'Xin sửa', noOut: 'Chưa chấm giờ về',
      newTitle: 'Bổ sung một ngày quên chấm công',
      fixing: 'Sửa chấm công ngày {d}',
      fixCancel: 'Chuyển sang bổ sung ngày mới',
      dateLbl: 'Ngày làm việc', inLbl: 'Giờ vào', outLbl: 'Giờ ra',
      overnight: 'Giờ ra sớm hơn giờ vào sẽ tính là ngày hôm sau.',
      reasonLbl: 'Lý do (bắt buộc)', reasonPh: 'Ví dụ: hết pin điện thoại, tổ trưởng có thể xác nhận',
      submit: 'Gửi yêu cầu', sending: 'Đang gửi…',
      note: 'Chỉ xin được trong 30 ngày; trước khi hợp tác xã duyệt, chấm công và lương không thay đổi.',
      listTitle: 'Yêu cầu của tôi', empty: 'Chưa có yêu cầu nào.', loading: 'Đang tải…',
      loadErr: 'Không tải được: {msg}',
      sent: 'Đã gửi ({id}), chờ hợp tác xã duyệt.', fail: 'Chưa gửi được: {msg}',
      cancel: 'Hủy', confirmCancel: 'Bạn chắc chắn muốn hủy yêu cầu này?', cancelFail: 'Hủy không thành công: {msg}',
      st_pending: 'Chờ duyệt', st_approved: 'Đã duyệt', st_rejected: 'Bị từ chối', st_cancelled: 'Đã hủy',
      kind_create: 'Bổ sung', kind_update: 'Sửa',
      rejectNote: 'Lý do từ chối: {n}', decisionNote: 'Ghi chú của hợp tác xã: {n}',
      errTime: 'Vui lòng nhập giờ vào và giờ ra.', errDate: 'Vui lòng chọn ngày làm việc.', errReason: 'Vui lòng nhập lý do.',
      errForbidden: 'Không có quyền xử lý yêu cầu của người này', errOffline: 'Không kết nối được máy chủ, vui lòng thử lại sau'
    },
    id: {
      title: 'Pengajuan koreksi absensi',
      hint: 'Lupa absen atau lupa absen pulang? Kirim pengajuan, koperasi akan memeriksa; setelah disetujui baru dicatat ke absensi dan gaji.',
      recentTitle: 'Absensi terbaru (tekan "Ajukan koreksi" untuk menambah jam pulang atau mengubah jam)',
      recentEmpty: 'Belum ada absensi terbaru.',
      fix: 'Ajukan koreksi', noOut: 'Belum absen pulang',
      newTitle: 'Tambah hari yang lupa absen',
      fixing: 'Koreksi absensi tanggal {d}',
      fixCancel: 'Beralih ke penambahan hari baru',
      dateLbl: 'Tanggal kerja', inLbl: 'Jam masuk', outLbl: 'Jam pulang',
      overnight: 'Jam pulang lebih awal dari jam masuk dianggap hari berikutnya.',
      reasonLbl: 'Alasan (wajib)', reasonPh: 'Contoh: baterai HP habis, mandor bisa memastikan',
      submit: 'Kirim pengajuan', sending: 'Mengirim…',
      note: 'Hanya dapat diajukan dalam 30 hari; absensi dan gaji tidak berubah sebelum koperasi menyetujui.',
      listTitle: 'Riwayat pengajuan saya', empty: 'Belum ada pengajuan.', loading: 'Memuat…',
      loadErr: 'Gagal memuat: {msg}',
      sent: 'Terkirim ({id}), menunggu persetujuan koperasi.', fail: 'Gagal mengirim: {msg}',
      cancel: 'Batalkan', confirmCancel: 'Yakin ingin membatalkan pengajuan ini?', cancelFail: 'Gagal membatalkan: {msg}',
      st_pending: 'Menunggu', st_approved: 'Disetujui', st_rejected: 'Ditolak', st_cancelled: 'Dibatalkan',
      kind_create: 'Tambah', kind_update: 'Koreksi',
      rejectNote: 'Alasan penolakan: {n}', decisionNote: 'Catatan koperasi: {n}',
      errTime: 'Isi jam masuk dan jam pulang.', errDate: 'Pilih tanggal kerja.', errReason: 'Isi alasan.',
      errForbidden: 'Tidak berwenang atas pengajuan pekerja ini', errOffline: 'Tidak dapat terhubung ke server, coba lagi nanti'
    }
  };

  var PILL = { pending: 'ad-pill ad-pill--warn', approved: 'ad-pill ad-pill--ok', rejected: 'ad-pill ad-pill--danger', cancelled: 'ad-pill' };

  var root = null, opts = {}, lang = 'zh';
  var fixing = null;        // 正在修正的出勤；null 表示補登新的一天
  var recent = null;        // { list } | { error }
  var requests = null;      // { list } | { error }
  var seq = 0;

  function T(k) { var d = TEXT[lang] || TEXT.zh; return d[k] !== undefined ? d[k] : TEXT.zh[k]; }
  function fill(tpl, vars) { return String(tpl).replace(/\{(\w+)\}/g, function (m, k) { return vars[k] != null ? vars[k] : m; }); }
  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; });
  }
  function el(id) { return root.querySelector('[data-ar="' + id + '"]'); }
  function workerId() { return typeof opts.workerId === 'function' ? opts.workerId() : opts.workerId; }
  function ymd(d) { return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2); }
  function hm(t) { return t ? String(t).slice(11, 16) : ''; }

  function api(path, init) {
    return fetch(API_BASE + path, init).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (body) {
        if (!res.ok || body.success === false) {
          throw new Error(res.status === 403 ? T('errForbidden') : (body.error || body.message || 'HTTP ' + res.status));
        }
        return body.data;
      });
    }, function () { throw new Error(T('errOffline')); });
  }

  function build() {
    root.innerHTML =
      '<div class="ad-card" style="padding: 24px; margin-top: 20px;">' +
        '<div class="ad-card__head"><h3 class="ad-heading" data-ar="title"></h3></div>' +
        '<p class="ad-caption" data-ar="hint" style="margin-bottom: 14px;"></p>' +
        '<div class="ad-label" data-ar="recentTitle" style="margin-bottom: 8px;"></div>' +
        '<div class="ad-list" data-ar="recent"></div>' +
        '<h4 class="ad-heading" data-ar="formTitle" style="margin: 20px 0 10px;"></h4>' +
        '<form data-ar="form" novalidate style="display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px 16px;">' +
          '<div class="ad-field"><label class="ad-label" data-ar="dateLbl"></label><input data-ar="date" type="date" class="ad-input"></div>' +
          '<div class="ad-field"><label class="ad-label" data-ar="inLbl"></label><input data-ar="in" type="time" class="ad-input" value="08:00"></div>' +
          '<div class="ad-field"><label class="ad-label" data-ar="outLbl"></label><input data-ar="out" type="time" class="ad-input" value="17:00"></div>' +
          '<div class="ad-field" style="grid-column: 1 / -1;"><label class="ad-label" data-ar="reasonLbl"></label>' +
            '<input data-ar="reason" type="text" class="ad-input" maxlength="200" autocomplete="off"><span class="ad-hint" data-ar="overnight"></span></div>' +
          '<div data-ar="msg" role="alert" style="grid-column: 1 / -1;" hidden></div>' +
          '<div style="grid-column: 1 / -1; display: flex; flex-wrap: wrap; align-items: center; gap: 12px;">' +
            '<button data-ar="submit" type="submit" class="ad-btn ad-btn--primary"></button>' +
            '<button data-ar="fixCancel" type="button" class="ad-btn ad-btn--ghost" hidden></button>' +
            '<span class="ad-caption" data-ar="note"></span>' +
          '</div>' +
        '</form>' +
        '<h4 class="ad-heading" data-ar="listTitle" style="margin: 24px 0 10px;"></h4>' +
        '<div class="ad-list" data-ar="list"></div>' +
      '</div>';
    el('form').addEventListener('submit', submit);
    el('fixCancel').addEventListener('click', function () { startFix(null); });
    root.addEventListener('click', onClick);
    var today = new Date();
    var d = el('date');
    d.max = ymd(today);
    d.min = ymd(new Date(today.getTime() - BACKDATE_DAYS * 86400000));
    d.value = ymd(new Date(today.getTime() - 86400000));
  }

  function renderStatic() {
    ['title', 'hint', 'recentTitle', 'dateLbl', 'inLbl', 'outLbl', 'reasonLbl', 'overnight', 'note', 'listTitle'].forEach(function (k) { el(k).textContent = T(k); });
    el('reason').placeholder = T('reasonPh');
    el('submit').textContent = T('submit');
    el('fixCancel').textContent = T('fixCancel');
    el('formTitle').textContent = fixing ? fill(T('fixing'), { d: fixing.workDate }) : T('newTitle');
    el('fixCancel').hidden = !fixing;
    el('date').disabled = !!fixing;
  }

  function renderRecent() {
    var box = el('recent');
    if (!recent) { box.innerHTML = '<p class="ad-caption">' + esc(T('loading')) + '</p>'; return; }
    if (recent.error) { box.innerHTML = '<div class="ad-notice ad-notice--danger" role="alert">' + esc(fill(T('loadErr'), { msg: recent.error })) + '</div>'; return; }
    if (!recent.list.length) { box.innerHTML = '<p class="ad-caption">' + esc(T('recentEmpty')) + '</p>'; return; }
    var today = ymd(new Date());
    box.innerHTML = recent.list.map(function (a) {
      var inProgress = !a.clockOutTime && a.workDate === today;   // 今天作業中：正常打下班卡即可
      var out = a.clockOutTime ? (String(a.clockOutTime).slice(0, 10) !== a.workDate ? '+1 ' : '') + hm(a.clockOutTime) : '';
      return '<div class="ad-row" style="align-items: center;">' +
        '<div class="ad-row__main"><div class="ad-row__title ad-num">' + esc(a.workDate) + '　' + esc(hm(a.clockInTime)) + '～' +
        (out ? esc(out) : '<span class="ad-pill ad-pill--danger">' + esc(T('noOut')) + '</span>') + '</div></div>' +
        (inProgress ? '' : '<button type="button" class="ad-btn ad-btn--secondary ad-btn--sm" data-fix="' + esc(a.id) + '">' + esc(T('fix')) + '</button>') +
        '</div>';
    }).join('');
  }

  function renderList() {
    var box = el('list');
    if (!requests) { box.innerHTML = '<p class="ad-caption">' + esc(T('loading')) + '</p>'; return; }
    if (requests.error) { box.innerHTML = '<div class="ad-notice ad-notice--danger" role="alert">' + esc(fill(T('loadErr'), { msg: requests.error })) + '</div>'; return; }
    if (!requests.list.length) { box.innerHTML = '<p class="ad-caption">' + esc(T('empty')) + '</p>'; return; }
    box.innerHTML = requests.list.map(function (r) {
      var note = r.decisionNote ? fill(T(r.status === 'rejected' ? 'rejectNote' : 'decisionNote'), { n: r.decisionNote }) : '';
      return '<div class="ad-row" style="align-items: flex-start;">' +
        '<div class="ad-row__main">' +
          '<div class="ad-row__title ad-num" style="white-space: normal;">' + esc(r.workDate) + '　' + esc(r.clockIn) + '～' + esc(r.clockOut) +
            ' <span class="ad-pill">' + esc(T('kind_' + r.kind)) + '</span></div>' +
          '<div class="ad-row__meta">' + esc(r.id) + '・' + esc(r.reason) + '</div>' +
          (note ? '<div class="ad-caption" style="margin-top: 4px;">' + esc(note) + '</div>' : '') +
        '</div>' +
        '<div style="display: flex; flex-direction: column; align-items: flex-end; gap: 8px; flex-shrink: 0;">' +
          '<span class="' + (PILL[r.status] || 'ad-pill') + '">' + esc(T('st_' + r.status)) + '</span>' +
          (r.cancellable ? '<button type="button" class="ad-btn ad-btn--ghost ad-btn--sm" data-cancel="' + esc(r.id) + '">' + esc(T('cancel')) + '</button>' : '') +
        '</div></div>';
    }).join('');
  }

  function render() { renderStatic(); renderRecent(); renderList(); }

  function showMsg(text, kind) {
    var m = el('msg');
    m.className = 'ad-notice ad-notice--' + (kind || 'danger');
    m.textContent = text;
    m.hidden = !text;
  }

  function startFix(a) {
    fixing = a;
    if (a) {
      el('date').value = a.workDate;
      el('in').value = hm(a.clockInTime);
      el('out').value = a.clockOutTime ? hm(a.clockOutTime) : '';
    } else {
      el('date').value = ymd(new Date(Date.now() - 86400000));
      el('in').value = '08:00';
      el('out').value = '17:00';
    }
    el('reason').value = '';
    showMsg('');
    renderStatic();
    if (a) el('form').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function load() {
    var id = workerId();
    var mine = ++seq;
    api('/attendance/history/' + encodeURIComponent(id)).then(function (list) {
      var min = ymd(new Date(Date.now() - BACKDATE_DAYS * 86400000));
      return { list: (list || []).filter(function (a) { return a.workDate >= min; }).slice(0, 10) };
    }).catch(function (e) { return { error: e.message }; }).then(function (r) {
      if (mine !== seq) return;
      recent = r;
      renderRecent();
    });
    api('/workers/' + encodeURIComponent(id) + '/attendance-requests').then(function (list) { return { list: list }; })
      .catch(function (e) { return { error: e.message }; }).then(function (r) {
        if (mine !== seq) return;
        requests = r;
        renderList();
      });
  }

  function submit(e) {
    e.preventDefault();
    var body = { clockIn: el('in').value, clockOut: el('out').value, reason: el('reason').value.trim() };
    if (fixing) body.attendanceId = fixing.id; else body.workDate = el('date').value;
    if (!fixing && !body.workDate) return showMsg(T('errDate'));
    if (!body.clockIn || !body.clockOut) return showMsg(T('errTime'));
    if (!body.reason) return showMsg(T('errReason'));
    showMsg('');
    var btn = el('submit');
    btn.disabled = true;
    btn.textContent = T('sending');
    api('/workers/' + encodeURIComponent(workerId()) + '/attendance-requests', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    }).then(function (r) {
      showMsg(fill(T('sent'), { id: r.id }), 'ok');
      el('reason').value = '';
      if (fixing) startFix(null);
      load();
      if (opts.onChange) opts.onChange();
    }).catch(function (err) {
      showMsg(fill(T('fail'), { msg: err.message }));
    }).then(function () {
      btn.disabled = false;
      btn.textContent = T('submit');
    });
  }

  function onClick(e) {
    var f = e.target.closest('[data-fix]');
    if (f) {
      var a = (recent && recent.list || []).filter(function (x) { return String(x.id) === f.dataset.fix; })[0];
      if (a) startFix(a);
      return;
    }
    var c = e.target.closest('[data-cancel]');
    if (c) {
      if (!window.confirm(T('confirmCancel'))) return;
      c.disabled = true;
      api('/attendance-requests/' + encodeURIComponent(c.dataset.cancel) + '/cancel', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
      }).then(function () { load(); }).catch(function (err) {
        c.disabled = false;
        showMsg(fill(T('cancelFail'), { msg: err.message }));
      });
    }
  }

  window.AttendanceRequest = {
    mount: function (container, options) {
      if (root === container && container.firstChild) {   // 切換分頁再進來：保留表單，只重讀
        opts = options || opts;
        load();
        return;
      }
      root = container;
      opts = options || {};
      lang = TEXT[opts.lang] ? opts.lang : 'zh';
      build();
      render();
      load();
    },
    setLang: function (l) {
      if (!root) return;
      lang = TEXT[l] ? l : 'zh';
      render();
    },
    reload: function () { if (root) load(); }
  };
})();
