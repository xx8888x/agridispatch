/**
 * 智農派工 (AgriDispatch) - 純前端 Mock API (demo-mock.js)
 *
 * demo 模式不需要後端：攔截 window.fetch 對 /api/* 的請求，改由 demo-fixtures.js 的錄製資料回應。
 *   - GET：回傳錄製時後端的真實回應（含查詢參數）；新增／修改／刪除過的資料以 localStorage 疊加。
 *   - POST/PUT/DELETE：常見 CRUD（訂單、工人、作物、人才、DM、工班名冊）會更新列表與明細；
 *     其他動作優先回傳錄製結果，沒有就回 { success: true }。
 *   - LINE Bot (localhost:5000)：直接回「離線」，頁面改用內建示範卡片。
 *
 * 載入：各資料夾 js/app-config.js 依 AGRI_CONFIG.mock 以 document.write 載入，須早於 api-client.js。
 * web/（production）不含本資料夾，同步腳本排除。
 *
 * 重新錄製（後端 demo 模式在 3000 執行時）：localStorage.agri_mock = 'record' 後逐頁瀏覽，
 * 收集到的請求清單在 top.__agriRec；再用 Software/tools/record-mock-fixtures.js 回放存檔。
 * 重置示範資料：AgriMock.reset()（示範導覽抽屜也有按鈕）。
 */
(function (global) {
  'use strict';

  var CFG = global.AGRI_CONFIG || {};
  if (CFG.mode === 'production') return;
  var override = null;
  try { override = global.localStorage.getItem('agri_mock'); } catch (e) {}
  var MODE = override || (CFG.mock === true ? 'mock' : CFG.mock);
  if (MODE !== 'mock' && MODE !== 'record') return;

  var nativeFetch = global.fetch ? global.fetch.bind(global) : null;
  var STATE_KEY = 'agri_mock_state_v1';

  // 由 URL 取出 /api 之後的路徑（含查詢字串）；不是 API 回傳 null
  function apiPath(url) {
    var abs;
    try { abs = new URL(url, global.location.href); } catch (e) { return null; }
    var i = abs.pathname.indexOf('/api/');
    if (i < 0) return null;
    return abs.pathname.slice(i + 4) + abs.search;
  }
  function isLineBot(url) { return /^https?:\/\/(localhost|127\.0\.0\.1):5000\//.test(url); }

  function reqInfo(input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || String(input);
    var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
    var body = init && typeof init.body === 'string' ? init.body : null;
    return { url: url, method: method, body: body };
  }

  // ------------------------------------------------------------------
  // 錄製模式：照常打後端，只記下請求清單（含 token），交給 Node 腳本回放存檔
  // ------------------------------------------------------------------
  if (MODE === 'record') {
    global.fetch = function (input, init) {
      var r = reqInfo(input, init);
      var p = apiPath(r.url);
      if (p !== null && !isLineBot(r.url)) {
        var h = new Headers((init && init.headers) || {});
        var rec;
        try { rec = global.top.__agriRec || (global.top.__agriRec = {}); } catch (e) { rec = global.__agriRec || (global.__agriRec = {}); }
        rec[r.method + ' ' + p + (r.body ? ' ' + r.body : '')] = { method: r.method, path: p, body: r.body, auth: h.get('Authorization') };
      }
      return nativeFetch(input, init);
    };
    return;
  }

  // ------------------------------------------------------------------
  // 回放模式
  // ------------------------------------------------------------------
  var FIX = global.AGRI_FIXTURES || {};

  function loadState() {
    try { return JSON.parse(global.localStorage.getItem(STATE_KEY)) || {}; } catch (e) { return {}; }
  }
  function saveState(s) {
    try { global.localStorage.setItem(STATE_KEY, JSON.stringify(s)); } catch (e) {}
  }
  var state = loadState();

  function clone(o) { return o == null ? o : JSON.parse(JSON.stringify(o)); }
  function get(key) {
    if (Object.prototype.hasOwnProperty.call(state, key)) return clone(state[key]);
    if (Object.prototype.hasOwnProperty.call(FIX, key)) return clone(FIX[key]);
    return undefined;
  }
  function put(key, entry) { state[key] = entry; saveState(state); }
  function del(key) { state[key] = { s: 404, b: { success: false, error: '資料不存在（示範資料）' } }; saveState(state); }

  function respond(status, body) {
    return new Response(JSON.stringify(body), {
      status: status,
      headers: { 'Content-Type': 'application/json;charset=UTF-8', 'X-Agri-Mock': '1' }
    });
  }

  function splitPath(p) {
    var q = p.indexOf('?');
    return { path: q < 0 ? p : p.slice(0, q), query: q < 0 ? '' : p.slice(q) };
  }

  // 找 GET 回應：完整路徑 → 去掉查詢字串 → 同前綴任一錄製（如不同日期參數）
  function findGet(p) {
    var hit = get('GET ' + p);
    if (hit) return hit;
    var sp = splitPath(p);
    hit = get('GET ' + sp.path);
    if (hit) return hit;
    var prefix = 'GET ' + sp.path + '?';
    var keys = Object.keys(state).concat(Object.keys(FIX));
    for (var i = 0; i < keys.length; i++) if (keys[i].indexOf(prefix) === 0) return get(keys[i]);
    return null;
  }

  // ---------------- 使用者／登入 ----------------
  var ROLE_BY_PAGE = [
    [/^(admin|vue-crud|insurance|skills|matching)/, 'admin_tech'],
    [/^(farmer|legalize|expenses)/, 'farmer'],
    [/^worker-foreign/, 'worker_foreign'],
    [/^worker-local|^worker-skills/, 'worker_local']
  ];
  function pageRole() {
    var file = (global.location.pathname.split('/').pop() || '').toLowerCase();
    for (var i = 0; i < ROLE_BY_PAGE.length; i++) if (ROLE_BY_PAGE[i][0].test(file)) return ROLE_BY_PAGE[i][1];
    return 'farmer';
  }
  function loginAs(role, refId) {
    var e = (refId && get('POST /auth/demo-login ' + JSON.stringify({ role: role, refId: refId }))) ||
      get('POST /auth/demo-login ' + JSON.stringify({ role: role }));
    if (e && e.b && e.b.user) {
      var b = e.b;
      b.token = 'mock.' + role + '.' + (b.user.refId || '');
      return respond(200, b);
    }
    return respond(200, { success: true, token: 'mock.' + role, user: { username: role, name: '示範使用者', role: role, refId: refId || null, demo: true } });
  }
  function currentUser() {
    try { return JSON.parse(global.sessionStorage.getItem('agri_user')); } catch (e) { return null; }
  }

  // ---------------- 通用 CRUD ----------------
  var ID_FIELDS = ['id', 'orderId', 'workerId', 'recordId'];
  function idOf(o) {
    if (!o || typeof o !== 'object') return undefined;
    for (var i = 0; i < ID_FIELDS.length; i++) if (o[ID_FIELDS[i]] != null) return String(o[ID_FIELDS[i]]);
    return undefined;
  }
  function listOf(entry) {
    if (!entry || !entry.b) return null;
    if (Array.isArray(entry.b.data)) return entry.b.data;
    if (Array.isArray(entry.b)) return entry.b;
    return null;
  }
  function nextId(list, sample) {
    var re = /^(.*?)(\d+)$/, best = null, width = 3, prefix = sample || 'MOCK-';
    (list || []).forEach(function (it) {
      var m = re.exec(idOf(it) || '');
      if (m && (best === null || +m[2] > best)) { best = +m[2]; prefix = m[1]; width = m[2].length; }
    });
    var n = String((best || 0) + 1);
    while (n.length < width) n = '0' + n;
    return prefix + n;
  }
  // 把列表中 id 相同的項目換掉（或刪掉）
  function patchList(listKey, id, item) {
    var e = get(listKey);
    var list = listOf(e);
    if (!list) return;
    for (var i = 0; i < list.length; i++) {
      if (idOf(list[i]) === id) {
        if (item) list[i] = Object.assign({}, list[i], item); else list.splice(i, 1);
        put(listKey, e);
        return;
      }
    }
  }

  // ---------------- 試算與建立訂單（移植自後端 OrderService.calculateOrder / calculatePackage） ----------------
  var EARLY_BIRD_RATE = 315, MAX_AREA = 10000, MAX_WORKERS = 30;
  var PACKAGE_SPECS = {
    'dm-mango-2026': ['mango', 'harvest_grading', 3.5],
    'dm-greenhouse-2026': ['taoyuan_veg', 'tillage_planting', 3.0],
    'dm-drone-2026': ['rice', 'drone_pest_control', 5.0],
    'dm-coldchain-2026': ['pineapple', 'cold_chain_pack', 4.0]
  };
  var CATEGORY = {
    harvest_grading: ['【採收分級】田間採收與外銷分級', 'team_a', '工班 A 組 (桃園溫室蔬菜組)'],
    tillage_planting: ['【整地種植】曳引機深耕與定植', 'team_c', '工班 C 組 (機耕無人機隊)'],
    pruning_bagging: ['【田間管理】修枝疏果與引蔓套袋', 'team_b', '工班 B 組 (跨區專線組)'],
    drone_pest_control: ['【科技植保】無人機精準噴藥施肥', 'team_c', '工班 C 組 (機耕無人機隊)'],
    cold_chain_pack: ['【冷鏈包裝】室內預冷與生鮮截切', 'team_d', '工班 D 組 (冷鏈常態組)'],
    typhoon_emergency: ['【緊急救火】防颱搶收 24H 馳援', 'team_b', '全工班跨區緊急馳援']
  };
  function blank(v) { return v == null || String(v).trim() === ''; }
  function findIn(listKey, id) {
    var list = listOf(get(listKey)) || [];
    for (var i = 0; i < list.length; i++) if (String(list[i].id) === String(id)) return list[i];
    return null;
  }
  function calcOrder(cropId, area, plan, zone, cat, reqWorkers) {
    if (reqWorkers != null && (reqWorkers < 1 || reqWorkers > MAX_WORKERS)) throw new Error('每日工人數 (workers) 必須介於 1 ～ ' + MAX_WORKERS);
    cat = blank(cat) ? 'harvest_grading' : cat;
    if (!CATEGORY[cat]) throw new Error('不支援的派工類別：' + cat);
    plan = blank(plan) ? 'early_bird' : plan;
    if (['early_bird', 'hourly_flex', 'turnkey'].indexOf(plan) < 0) throw new Error('方案 (plan) 必須為 early_bird / hourly_flex / turnkey');
    zone = blank(zone) ? 'core_35km' : zone;
    if (!(area > 0) || !isFinite(area)) throw new Error('面積 (area) 必須大於 0');
    if (area > MAX_AREA) throw new Error('面積 (area) 超過上限 ' + MAX_AREA + ' 分地');
    if (blank(cropId)) throw new Error('作物代號 (cropId) 為必填欄位');
    var crop = findIn('GET /crops', cropId);
    if (!crop) throw new Error('查無作物代號：' + cropId);
    var unitRate = crop.rate, c = CATEGORY[cat];
    if (cat === 'typhoon_emergency') unitRate = Math.max(unitRate, 360);
    var totalHours = Math.round(crop.baseHoursPerFen * area);
    var workers = reqWorkers != null ? reqWorkers : Math.max(2, Math.round(crop.workersPerFen * (area > 3 ? 1.5 : 1)));
    var estDays = Math.round((totalHours / (workers * 8)) * 10) / 10;
    var discount = 1, surcharge = zone === 'cross_region' ? 1200 : 0, planName;
    if (plan === 'early_bird') {
      if (cat !== 'typhoon_emergency') unitRate = EARLY_BIRD_RATE;
      planName = '【Tier 1】核心外展移工早鳥包 ($315/hr・鎖定名額・優先排班・20%定金)';
    } else if (plan === 'hourly_flex') {
      if (cat !== 'typhoon_emergency') unitRate = 310;
      planName = '【Tier 2】在地彈性兼職包 (隨叫隨派・時薪 $310・零底薪負擔)';
    } else {
      discount = 0.95;
      planName = '【Tier 3】成果統包專業代耕 (無人機/機耕驗收付款・免監工)';
    }
    var standard = Math.round(totalHours * unitRate), discounted = Math.round(standard * discount);
    return {
      cropId: crop.id, cropName: crop.name, area: area, plan: plan, planName: planName,
      dispatchCategory: cat, categoryName: c[0], suggestedTeam: c[1], suggestedTeamName: c[2],
      transportZone: zone, transportSurcharge: surcharge, totalHours: totalHours,
      suggestedWorkers: workers, requestedWorkers: reqWorkers, estDays: estDays, unitRate: unitRate,
      standardTotal: standard + surcharge, actualTotal: discounted + surcharge,
      deposit: plan === 'early_bird' ? Math.round(discounted * 0.2) : (plan === 'turnkey' ? Math.round(discounted * 0.3) : 0),
      savings: standard - discounted
    };
  }
  function calculate(req) {
    var zone = blank(req.zone) ? (blank(req.transportZone) ? 'core_35km' : req.transportZone) : req.zone;
    var w = req.workers != null ? req.workers : req.requestedWorkers;
    var reqWorkers = blank(w) ? null : Math.round(Number(w));
    if (reqWorkers !== null && isNaN(reqWorkers)) throw new Error('每日工人數 (workers) 必須為數字');
    if (!blank(req.packageId)) {
      var spec = PACKAGE_SPECS[String(req.packageId).trim()];
      if (!spec) throw new Error('此工時包尚未開放線上預約：' + req.packageId);
      var b = findIn('GET /brochures', req.packageId);
      if (!b || b.status !== 'active') throw new Error('工時包已下架或不存在：' + req.packageId);
      var crew = reqWorkers != null ? reqWorkers : Math.max(1, b.workers);
      var d = calcOrder(spec[0], spec[2], 'early_bird', zone, spec[1], crew);
      var name = '【工時包】' + b.title;
      return Object.assign(d, {
        packageId: req.packageId, plan: 'early_bird', planName: name.slice(0, 100), totalHours: b.hours,
        suggestedWorkers: crew, requestedWorkers: reqWorkers, estDays: Math.round((b.hours / (crew * 8)) * 10) / 10,
        unitRate: Math.round(b.discountPrice / b.hours), standardTotal: b.originalPrice + d.transportSurcharge,
        actualTotal: b.discountPrice + d.transportSurcharge, deposit: b.depositAmount,
        savings: Math.max(0, b.originalPrice - b.discountPrice)
      });
    }
    var area = req.area != null ? parseFloat(req.area) : 5;
    return calcOrder(blank(req.cropId) ? 'taoyuan_veg' : req.cropId, area, req.plan, zone, req.dispatchCategory, reqWorkers);
  }
  function createOrder(o) {
    if (blank(o.farmerName)) throw new Error('農場主姓名為必填');
    var u = currentUser() || {};
    var calc = calculate({
      cropId: o.cropId, area: o.area, plan: o.plan, zone: o.transportZone,
      dispatchCategory: o.dispatchCategory, workers: o.requestedWorkers, packageId: o.packageId
    });
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    var now = new Date();
    var order = Object.assign({}, o, {
      farmerId: o.farmerId || (u.role === 'farmer' ? u.refId : null),
      plan: calc.plan, transportZone: calc.transportZone, dispatchCategory: calc.dispatchCategory,
      cropId: calc.cropId, area: calc.area,
      foremanName: blank(o.foremanName) ? o.farmerName + ' (本人帶隊)' : o.foremanName,
      foremanPhone: blank(o.foremanPhone) ? o.phone : o.foremanPhone,
      cropName: calc.cropName, planName: calc.planName, categoryName: calc.categoryName,
      totalHours: calc.totalHours, suggestedWorkers: calc.suggestedWorkers, unitRate: calc.unitRate,
      standardAmount: calc.standardTotal, finalAmount: calc.actualTotal, depositAmount: calc.deposit,
      transportSurcharge: calc.transportSurcharge,
      assignedTeamId: blank(o.assignedTeamId) ? calc.suggestedTeam : o.assignedTeamId,
      depositPaid: false, status: calc.deposit > 0 ? 'pending' : 'confirmed',
      createdAt: now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()) + ' ' +
        pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds())
    });
    var e = get('GET /orders') || { s: 200, b: { success: true, data: [] } };
    var list = listOf(e);
    order.id = nextId(list.filter(function (x) { return /^ORD-\d{4}-\d+$/.test(x.id); }), 'ORD-' + now.getFullYear() + '-');
    list.push(order);
    put('GET /orders', e);
    put('GET /orders/' + encodeURIComponent(order.id), { s: 200, b: { success: true, data: order } });
    return order;
  }

  // ---------------- 模擬金流：定金付款直接成功，訂單 pending → confirmed ----------------
  function checkout(b) {
    var oid = b.orderId;
    var dKey = 'GET /orders/' + encodeURIComponent(oid);
    var d = get(dKey);
    var order = (d && d.b && d.b.data) || findIn('GET /orders', oid);
    if (!order) return respond(404, { success: false, error: '查無訂單：' + oid });
    if (order.status === 'cancelled') return respond(409, { success: false, error: '訂單已取消，無法付款' });
    if (!(order.depositAmount > 0)) return respond(400, { success: false, error: '此方案無須預付定金' });
    var already = !!order.depositPaid;
    var patch = { depositPaid: true, status: order.status === 'pending' ? 'confirmed' : order.status };
    patchList('GET /orders', String(order.id), patch);
    put(dKey, { s: 200, b: { success: true, data: Object.assign({}, order, patch) } });
    var now = new Date(), stamp = now.getTime().toString().slice(-8);
    return respond(200, {
      success: true, transactionId: 'TX-MOCK-' + stamp, orderId: order.id, farmerName: order.farmerName,
      provider: 'mock', paymentMethod: b.paymentMethod || 'credit_card', amountPaid: order.depositAmount,
      currency: 'TWD', status: 'SUCCESS', authCode: stamp.slice(-6), invoiceNo: 'AG-' + stamp,
      paymentTime: now.toISOString().slice(0, 19).replace('T', ' '), orderStatus: patch.status, alreadyPaid: already,
      message: already ? '此訂單定金已付清，無需重複付款'
        : '定金 NT$ ' + order.depositAmount.toLocaleString() + ' 元已成功支付！訂單已轉為【已排定】。'
    });
  }

  // ---------------- 工班合法化清冊（移植自 LegalizeService.toView / aggregate） ----------------
  function legalizeType(key) {
    return (listOf(get('GET /legalize/types')) || []).filter(function (t) { return t.key === key; })[0] || null;
  }
  function legalizeView(w) {
    var t = legalizeType(w.type), n = t ? t.steps.length : 0;
    var done = [];
    for (var i = 0; i < n; i++) done.push(!!(w.done && w.done[i]));
    var all = n > 0 && done.every(Boolean), replace = t && t.path === 'replace';
    w.typeLabel = t ? t.label : w.type;
    w.path = t ? t.path : null;
    w.done = done;
    w.doneCount = done.filter(Boolean).length;
    w.stepCount = n;
    w.isDone = all;
    w.isStopped = !!(replace && done[0]);
    w.status = replace ? (all ? 'replaced' : (done[0] ? 'stopped' : 'in_use')) : (all ? 'legalized' : 'in_progress');
    w.updatedAt = new Date().toISOString().slice(0, 19);
    return w;
  }
  function legalizeSummary(rows) {
    var e = get('GET /legalize/summary');
    if (!e || !e.b || !e.b.data || !e.b.data.farms) return;
    var me = (currentUser() || {}).refId || 'F001';
    var keys = ['total', 'direct', 'conditional', 'replace', 'legalized', 'inProgress', 'exposed', 'stopped', 'replaced', 'gapOpen', 'exposureMinWan', 'exposureMaxWan'];
    var m = {};
    keys.forEach(function (k) { m[k] = 0; });
    rows.forEach(function (w) {
      m.total++;
      m[w.path === 'direct' ? 'direct' : w.path === 'conditional' ? 'conditional' : 'replace']++;
      if (w.path === 'replace') m[w.status === 'replaced' ? 'replaced' : w.status === 'stopped' ? 'stopped' : 'exposed']++;
      else m[w.status === 'legalized' ? 'legalized' : 'inProgress']++;
    });
    m.gapOpen = m.exposed + m.stopped;
    m.exposureMinWan = m.exposed * 15;
    m.exposureMaxWan = m.exposed * 75;
    var d = e.b.data;
    d.farms.forEach(function (f) { if (f.farmerId === me) keys.forEach(function (k) { f[k] = m[k]; }); });
    if (d.totals) keys.forEach(function (k) { d.totals[k] = d.farms.reduce(function (s, f) { return s + (f[k] || 0); }, 0); });
    put('GET /legalize/summary', e);
  }
  function legalizeWrite(method, id, b) {
    var key = 'GET /legalize/roster', e = get(key);
    if (!e) return respond(404, { success: false, error: '查無清冊' });
    var rows = listOf(e), w = null, idx = -1;
    rows.forEach(function (r, i) { if (r.id === id) { w = r; idx = i; } });
    if (method === 'POST') {
      var t = legalizeType(b.type);
      if (!t) return respond(400, { success: false, error: '不支援的身分類型：' + b.type });
      if (t.path !== 'replace' && blank(b.name)) return respond(400, { success: false, error: '請填寫稱呼' });
      var now = new Date().toISOString().slice(0, 19);
      w = legalizeView({
        id: nextId(rows, 'LGW-'), farmerId: (currentUser() || {}).refId || 'F001', type: b.type,
        name: t.path === 'replace' ? null : b.name, job: b.job || '農務',
        daysPerWeek: Math.min(7, Math.max(1, parseInt(b.daysPerWeek, 10) || 1)), done: [], createdAt: now
      });
      rows.push(w);
    } else if (!w) {
      return respond(404, { success: false, error: '查無此人員：' + id });
    } else if (method === 'DELETE') {
      rows.splice(idx, 1);
    } else {
      if (b.stepIndex != null) w.done[b.stepIndex] = !!b.checked;
      if (Array.isArray(b.done)) w.done = b.done.slice();
      ['name', 'job'].forEach(function (f) { if (b[f] != null) w[f] = b[f]; });
      if (b.daysPerWeek != null) w.daysPerWeek = b.daysPerWeek;
      legalizeView(w);
    }
    put(key, e);
    legalizeSummary(rows);
    return respond(200, method === 'DELETE' ? { success: true } : { success: true, data: w });
  }

  // ---------------- 派工媒合：確認工班與出工名單 ----------------
  function saveCrew(orderId, b) {
    var enc = encodeURIComponent(orderId);
    var mk = 'GET /matching/orders/' + enc, me = get(mk);
    if (!me || !me.b || !me.b.data) return respond(404, { success: false, error: '查無訂單：' + orderId });
    if (blank(b.teamId) || !Array.isArray(b.crew) || !b.crew.length) return respond(400, { success: false, error: '請選擇工班與至少 1 位工人' });
    var ck = 'GET /matching/orders/' + enc + '/crew?teamId=' + encodeURIComponent(b.teamId), ce = get(ck);
    if (ce && ce.b && ce.b.data) {
      ce.b.data.currentCrew = b.crew.slice();
      (ce.b.data.candidates || []).forEach(function (c) { c.onCurrentCrew = b.crew.indexOf(c.workerId) >= 0; });
      put(ck, ce);
    }
    var rec = me.b.data;
    rec.order.assignedTeamId = b.teamId;
    rec.order.crew = b.crew.slice();
    if (ce && ce.b && ce.b.data) rec.crew = clone(ce.b.data);
    put(mk, me);
    patchList('GET /orders', orderId, { assignedTeamId: b.teamId });
    var od = get('GET /orders/' + enc);
    if (od && od.b && od.b.data) { od.b.data.assignedTeamId = b.teamId; put('GET /orders/' + enc, od); }
    return respond(200, { success: true, data: rec });
  }
  // 訂單工期改了，媒合頁的訂單摘要一起更新
  function syncMatchingOrder(orderId, fields) {
    var mk = 'GET /matching/orders/' + encodeURIComponent(orderId), me = get(mk);
    if (!me || !me.b || !me.b.data || !me.b.data.order) return;
    ['startDate', 'endDate', 'assignedTeamId', 'status'].forEach(function (f) { if (fields[f] != null) me.b.data.order[f] = fields[f]; });
    put(mk, me);
  }

  // ---------------- 領班逐人評核 ----------------
  // 錄製資料只到錄製日；查詢的日期沒有班次時，改用該工班最近一天有班次的資料並換成查詢日期，
  // 路演當天預設的「昨天」也有人可評。第一次查詢後存進 state，評核結果才留得住。
  // 「本人班次」依目前登入者判斷（錄製時用的是 admin，self 一律 false）
  function markSelf(e) {
    var me = (currentUser() || {}).refId;
    var out = clone(e);
    (out.b && out.b.data && out.b.data.shifts || []).forEach(function (s) { s.self = !!me && s.workerId === me; });
    return out;
  }
  function ratable(e) {
    var me = (currentUser() || {}).refId;
    return (e && e.b && e.b.data && e.b.data.shifts || []).some(function (s) { return s.clockOut && s.workerId !== me; });
  }
  function evaluationsFor(p, teamId, date) {
    var key = 'GET ' + p;
    var hit = get(key);
    if (hit && (Object.prototype.hasOwnProperty.call(state, key) || ratable(hit))) return markSelf(hit);
    var prefix = 'GET /evaluations/teams/' + encodeURIComponent(teamId) + '?date=';
    var best = null;
    Object.keys(FIX).forEach(function (k) {
      if (k.indexOf(prefix) === 0 && ratable(FIX[k]) && (!best || k > best)) best = k;
    });
    if (!best) return hit || { s: 200, b: { success: true, data: { teamId: teamId, date: date, shifts: [] } } };
    var e = clone(FIX[best]);
    var from = e.b.data.date;
    e.b.data.date = date;
    e.b.data.shifts.forEach(function (s) {
      s.attendanceId = String(s.attendanceId) + '@' + date;
      ['clockIn', 'clockOut', 'workDate'].forEach(function (f) {
        if (typeof s[f] === 'string') s[f] = s[f].split(from).join(date);
      });
    });
    put(key, e);
    return markSelf(e);
  }
  function rateShift(attendanceId, b) {
    if (['excellent', 'ok', 'needs_improvement'].indexOf(b.rating) < 0 && b.rating != null) {
      return respond(400, { success: false, error: '評核只能是 excellent／ok／needs_improvement' });
    }
    var found = null, u = currentUser() || {};
    Object.keys(state).concat(Object.keys(FIX)).forEach(function (k) {
      if (found || k.indexOf('GET /evaluations/teams/') !== 0) return;
      var e = get(k);
      (e && e.b && e.b.data && e.b.data.shifts || []).forEach(function (s) {
        if (String(s.attendanceId) !== String(attendanceId)) return;
        if (u.refId && s.workerId === u.refId) { found = { err: '本人班次，請由其他領班或合作社評核' }; return; }
        s.rating = b.rating;
        s.ratedBy = u.refId || u.username || null;
        found = { key: k, entry: e, shift: s };
      });
    });
    if (!found) return respond(404, { success: false, error: '找不到出勤紀錄：' + attendanceId });
    if (found.err) return respond(409, { success: false, error: found.err });
    put(found.key, found.entry);
    return respond(200, { success: true, data: found.shift });
  }

  // ---------------- 技能標籤複核恢復：解除暫停、標籤恢復有效 ----------------
  function restoreSkill(workerId, skillKey, b) {
    var note = b && b.note ? String(b.note).trim() : '';
    if (note.length > 200) return respond(400, { success: false, error: '備註最多 200 字' });
    var profile = null;
    function fix(p) {
      if (!p || p.workerId !== workerId) return;
      (p.skills || []).forEach(function (s) {
        if (s.key === skillKey) { s.suspended = false; s.suspendReasons = []; s.active = s.level !== 'none'; }
      });
      // 同 SkillService.profile：沒有暫停中的標籤了，但近 6 個月曾暫停 → 仍不給履歷認證
      var reasons = (p.unverifiedReasons || []).filter(function (r) { return r !== '有標籤暫停中，待合作社複核'; });
      if ((p.skills || []).some(function (s) { return s.suspended; })) reasons.push('有標籤暫停中，待合作社複核');
      if (reasons.indexOf('近 6 個月曾有標籤暫停') < 0) reasons.push('近 6 個月曾有標籤暫停');
      p.unverifiedReasons = reasons;
      p.verified = false;
      profile = p;
    }
    var lk = 'GET /skills/workers', le = get(lk);
    (listOf(le) || []).forEach(fix);
    if (le) put(lk, le);
    var dk = 'GET /skills/workers/' + encodeURIComponent(workerId), de = get(dk);
    if (de && de.b && de.b.data) { fix(de.b.data); put(dk, de); }
    if (!profile) return respond(404, { success: false, error: '找不到工人：' + workerId });
    return respond(200, { success: true, data: profile });
  }

  // ---------------- 保險加保／退保 ----------------
  var INS_LABEL = { labor: '勞保', employment: '就保', occupational: '職保', health: '健保' };
  function insView(workerId) {
    var key = 'GET /insurance/workers/' + encodeURIComponent(workerId);
    var e = get(key);
    if (!e || !e.b || !e.b.data) {
      var li = (listOf(get('GET /insurance/workers')) || []).filter(function (w) { return w.workerId === workerId; })[0];
      if (!li) return null;
      e = { s: 200, b: { success: true, data: clone(li) } };
    }
    return { key: key, entry: e, w: e.b.data };
  }
  function saveIns(v) {
    var w = v.w;
    w.active = w.active || {};
    w.missing = (w.required || []).filter(function (t) { return !w.active[t]; });
    w.fullyInsured = w.missing.length === 0;
    put(v.key, v.entry);
    var listKey = 'GET /insurance/workers', le = get(listKey), list = listOf(le);
    if (list) {
      for (var i = 0; i < list.length; i++) {
        if (list[i].workerId === w.workerId) {
          list[i].active = clone(w.active);
          list[i].missing = clone(w.missing);
          list[i].fullyInsured = w.fullyInsured;
          if ('records' in list[i]) list[i].records = clone(w.records);
        }
      }
      put(listKey, le);
    }
  }
  function enroll(workerId, b) {
    var v = insView(workerId);
    if (!v) return respond(404, { success: false, error: '查無工人：' + workerId });
    if (!INS_LABEL[b.type]) return respond(400, { success: false, error: '不支援的保險類別：' + b.type });
    if (v.w.active && v.w.active[b.type]) return respond(409, { success: false, error: INS_LABEL[b.type] + '已在保，請先退保再重新加保' });
    var date = b.enrollDate || new Date().toISOString().slice(0, 10);
    var rec = {
      id: 'INS-' + workerId + '-' + b.type + '-' + date.replace(/-/g, ''), workerId: workerId, type: b.type,
      typeLabel: INS_LABEL[b.type], insuredSalary: b.insuredSalary || v.w.baseSalary, enrollDate: date,
      withdrawDate: null, withdrawReason: null, filingRef: b.filingRef || '示範資料', active: true
    };
    v.w.active = v.w.active || {};
    v.w.active[b.type] = rec;
    (v.w.records = v.w.records || []).push(rec);
    var warnings = [];
    if (rec.insuredSalary < 29500) warnings.push('投保薪資低於基本工資 29,500 元【待確認】');
    if ((v.w.required || []).indexOf(b.type) < 0) warnings.push(INS_LABEL[b.type] + '不在此工人的應加保項目內');
    saveIns(v);
    return respond(200, { success: true, data: rec, warnings: warnings });
  }
  function withdraw(recordId, b) {
    var m = /^INS-(.+?)-(labor|employment|occupational|health)-/.exec(recordId);
    var v = m && insView(m[1]);
    if (!v) return respond(404, { success: false, error: '查無投保紀錄：' + recordId });
    var rec = null;
    (v.w.records || []).forEach(function (r) { if (r.id === recordId) rec = r; });
    if (!rec && v.w.active && v.w.active[m[2]] && v.w.active[m[2]].id === recordId) {
      rec = v.w.active[m[2]];
      (v.w.records = v.w.records || []).push(rec);
    }
    if (!rec) return respond(404, { success: false, error: '查無投保紀錄：' + recordId });
    rec.withdrawDate = b.withdrawDate || new Date().toISOString().slice(0, 10);
    rec.withdrawReason = b.reason || null;
    rec.active = false;
    if (v.w.active && v.w.active[rec.type] && v.w.active[rec.type].id === recordId) delete v.w.active[rec.type];
    saveIns(v);
    return respond(200, { success: true, data: rec });
  }

  function handleWrite(method, p, bodyText) {
    var sp = splitPath(p);
    var body = null;
    try { body = bodyText ? JSON.parse(bodyText) : null; } catch (e) {}
    var seg = sp.path.split('/').filter(Boolean);

    // 登入
    if (sp.path === '/auth/demo-login') return loginAs((body && body.role) || pageRole(), body && body.refId);
    if (sp.path === '/auth/login') {
      var rec = get('POST /auth/login ' + bodyText);
      if (rec && rec.b && rec.b.success) { rec.b.token = 'mock.' + rec.b.user.role + '.' + (rec.b.user.refId || ''); return respond(200, rec.b); }
      // 沒錄到的帳號：示範模式任意帳密都放行，依登入頁角色登入；工人帳號對照示範清單的 workerId
      var role = pageRole(), refId = null, acc = global.AGRI_DEMO_ACCOUNTS || {};
      var group = role === 'worker_local' ? acc.workerLocal : role === 'worker_foreign' ? acc.workerForeign : null;
      ((group && group.profiles) || []).forEach(function (pf) { if (body && pf.identifier === body.identifier) refId = pf.workerId; });
      return loginAs(role, refId);
    }

    // 有業務邏輯、會影響後續查詢的動作
    if (method === 'POST' && sp.path === '/orders/calculate') {
      try { return respond(200, { success: true, data: calculate(body || {}) }); } catch (err) { return respond(400, { success: false, error: err.message }); }
    }
    if (method === 'POST' && sp.path === '/orders') {
      try { return respond(200, { success: true, data: createOrder(body || {}) }); } catch (err) { return respond(400, { success: false, error: err.message }); }
    }
    if (method === 'POST' && sp.path === '/payments/checkout') return checkout(body || {});
    if (method === 'PUT' && /^\/evaluations\/attendance\/[^/]+$/.test(sp.path)) return rateShift(decodeURIComponent(seg[2]), body || {});
    if (method === 'PUT' && /^\/matching\/orders\/[^/]+$/.test(sp.path)) return saveCrew(decodeURIComponent(seg[2]), body || {});
    if (method === 'PUT' && /^\/orders\/[^/]+$/.test(sp.path) && body) {
      if (body.startDate && body.endDate && body.endDate < body.startDate) return respond(400, { success: false, error: '完工日不可早於開工日' });
      syncMatchingOrder(decodeURIComponent(seg[1]), body);
    }
    if (method === 'POST' && /^\/skills\/workers\/[^/]+\/[^/]+\/restore$/.test(sp.path)) {
      return restoreSkill(decodeURIComponent(seg[2]), decodeURIComponent(seg[3]), body);
    }
    if (sp.path === '/legalize/roster' && method === 'POST') return legalizeWrite('POST', null, body || {});
    if (/^\/legalize\/roster\/(?!reset$)[^/]+$/.test(sp.path)) return legalizeWrite(method, decodeURIComponent(seg[2]), body || {});
    if (method === 'POST' && (sp.path === '/dispatch/weather-scenario' || sp.path === '/dispatch/weather-switch')) {
      var sid = sp.path === '/dispatch/weather-switch'
        ? (body && body.isRainMode ? 'heavy_rain' : 'sunny')
        : ((body && body.scenarioId) || 'sunny');
      // 套用錄製的情境快照，換頁後調度總覽、工班狀態仍維持此情境
      var prefix = 'SCENARIO ' + sid + ' ';
      Object.keys(FIX).forEach(function (k) {
        if (k.indexOf(prefix) === 0) state[k.slice(prefix.length)] = clone(FIX[k]);
      });
      saveState(state);
      var sr = FIX['POST /dispatch/weather-scenario ' + JSON.stringify({ scenarioId: sid })];
      return respond(200, sr ? sr.b : { success: true, message: '已切換天候情境' });
    }
    if (method === 'POST' && /^\/insurance\/workers\/[^/]+\/enroll$/.test(sp.path)) return enroll(decodeURIComponent(seg[2]), body || {});
    if (method === 'POST' && /^\/insurance\/records\/[^/]+\/withdraw$/.test(sp.path)) return withdraw(decodeURIComponent(seg[2]), body || {});
    if (method === 'POST' && (sp.path === '/dev/seed' || sp.path === '/brochures/seed' || sp.path === '/legalize/roster/reset')) {
      var prefixes = { '/dev/seed': [''], '/brochures/seed': ['GET /brochures'], '/legalize/roster/reset': ['GET /legalize/'] }[sp.path];
      Object.keys(state).forEach(function (k) {
        if (prefixes.some(function (px) { return k.indexOf(px) === 0; })) delete state[k];
      });
      saveState(state);
      var rec0 = get(method + ' ' + p);
      return respond(200, rec0 ? rec0.b : { success: true, message: '示範資料已重置' });
    }

    // 錄製過完全相同的請求：直接回放（推播、媒合等有業務邏輯的動作）
    var exact = get(method + ' ' + p + (bodyText ? ' ' + bodyText : ''));

    // 集合 CRUD：POST /x、PUT /x/{id}、PUT /x/{id}/status、DELETE /x/{id}
    var collKey = 'GET /' + seg.slice(0, seg.length - (method === 'POST' ? 0 : 1)).join('/');
    if (method === 'POST' && listOf(get('GET ' + sp.path)) && !exact) {
      var listKey = 'GET ' + sp.path;
      var e = get(listKey), list = listOf(e);
      var item = Object.assign({}, body || {});
      if (idOf(item) === undefined) item.id = nextId(list);
      list.unshift(item);
      put(listKey, e);
      put('GET ' + sp.path + '/' + idOf(item), { s: 200, b: { success: true, data: item } });
      return respond(200, { success: true, data: item });
    }
    if ((method === 'PUT' || method === 'DELETE') && seg.length >= 2 && listOf(get(collKey))) {
      var id = decodeURIComponent(seg[seg.length - 1]);
      var detailKey = 'GET ' + sp.path;
      if (method === 'DELETE') {
        patchList(collKey, id, null);
        del(detailKey);
        return respond(200, { success: true });
      }
      var cur = get(detailKey);
      var merged = Object.assign({}, (cur && cur.b && cur.b.data) || {}, body || {});
      patchList(collKey, id, body || {});
      put(detailKey, { s: 200, b: { success: true, data: merged } });
      return respond(200, exact ? exact.b : { success: true, data: merged });
    }
    if (method === 'PUT' && seg.length >= 3 && seg[seg.length - 1] === 'status') {
      // PUT /orders/{id}/status
      var base = '/' + seg.slice(0, seg.length - 2).join('/');
      var oid = decodeURIComponent(seg[seg.length - 2]);
      patchList('GET ' + base, oid, body || {});
      var d = get('GET ' + base + '/' + seg[seg.length - 2]);
      if (d && d.b && d.b.data) { Object.assign(d.b.data, body || {}); put('GET ' + base + '/' + seg[seg.length - 2], d); }
      return respond(200, { success: true, data: d && d.b ? d.b.data : body });
    }

    if (exact) return respond(exact.s, exact.b);
    var loose = get(method + ' ' + p);
    if (loose) return respond(loose.s, loose.b);
    var keys = Object.keys(FIX), prefix = method + ' ' + p + ' ';
    for (var i = 0; i < keys.length; i++) if (keys[i].indexOf(prefix) === 0) return respond(FIX[keys[i]].s, FIX[keys[i]].b);
    // 沒錄到的動作：只回成功，不帶 data，讓頁面沿用自己的本地更新／離線 fallback
    return respond(200, { success: true, message: '示範模式：已模擬送出' });
  }

  function handle(method, p, bodyText) {
    var sp = splitPath(p);
    if (method === 'GET') {
      if (sp.path === '/auth/me') {
        var u = currentUser();
        return u ? respond(200, { success: true, user: u, data: u }) : respond(401, { success: false, error: '未登入' });
      }
      var em = /^\/evaluations\/teams\/([^/]+)$/.exec(sp.path);
      if (em) {
        var ev = evaluationsFor(p, decodeURIComponent(em[1]), (/[?&]date=([^&]+)/.exec(sp.query) || [])[1] || '');
        return respond(ev.s, ev.b);
      }
      var pm = /^\/payments\/([^/]+)\/status$/.exec(sp.path);
      if (pm) {
        var od = get('GET /orders/' + pm[1]);
        var o = (od && od.b && od.b.data) || findIn('GET /orders', decodeURIComponent(pm[1]));
        if (o) {
          var base = findGet(p);
          return respond(200, { success: true, data: {
            orderId: o.id, farmerName: o.farmerName, finalAmount: o.finalAmount,
            depositAmount: o.depositAmount, depositRequired: o.depositAmount > 0, depositPaid: !!o.depositPaid,
            orderStatus: o.status, transactions: (base && base.b && base.b.data && base.b.data.transactions) || []
          } });
        }
      }
      var hit = findGet(p);
      if (hit) return respond(hit.s, hit.b);
      if (global.console) console.warn('[demo-mock] 沒有示範資料：GET ' + p);
      return respond(404, { success: false, error: '示範資料未涵蓋此查詢' });
    }
    return handleWrite(method, p, bodyText);
  }

  global.fetch = function (input, init) {
    var r = reqInfo(input, init);
    if (isLineBot(r.url)) return Promise.reject(new TypeError('示範模式：LINE Bot 服務未啟動'));
    var p = apiPath(r.url);
    if (p === null) return nativeFetch(input, init);
    // LINE webhook 交給 line.html 的本地示範卡片（mockLocalResponse），比通用成功回應完整
    if (p.indexOf('/line/webhook') === 0) return Promise.reject(new TypeError('示範模式：改用本頁示範卡片'));
    return new Promise(function (resolve) {
      // 模擬一點網路延遲，讓載入狀態看得到
      setTimeout(function () { resolve(handle(r.method, p, r.body)); }, 60);
    });
  };

  global.AgriMock = {
    mode: MODE,
    reset: function () {
      state = {};
      try { global.localStorage.removeItem(STATE_KEY); } catch (e) {}
      try { global.sessionStorage.removeItem('agri_token'); global.sessionStorage.removeItem('agri_user'); } catch (e) {}
    }
  };
})(window);
