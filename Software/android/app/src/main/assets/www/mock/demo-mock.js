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

  // 我的農場（/farmers/me/farms，可多個）：依登入者 refId 存在 state，檢查與後端 FarmProfileService 相同
  function farmKey() {
    var me = currentUser();
    return me && me.role === 'farmer' && me.refId ? 'GET /farmers/me/farms#' + me.refId : null;
  }
  function farmList(k) {
    var e = get(k);
    return (e && e.b && e.b.data) || [];
  }
  function farmApi(method, id, r) {
    var k = farmKey();
    if (!k) return respond(403, { success: false, error: 'FORBIDDEN' });
    var list = farmList(k);
    if (method === 'GET') return respond(200, { success: true, data: list });
    var idx = -1;
    list.forEach(function (f, i) { if (f.id === id) idx = i; });
    if ((method === 'PUT' || method === 'DELETE') && idx < 0) return respond(404, { success: false, error: '找不到這個農場' });
    if (method === 'DELETE') {
      list.splice(idx, 1);
      put(k, { s: 200, b: { success: true, data: list } });
      return respond(200, { success: true, message: '農場已刪除' });
    }
    r = r || {};
    var tel = String(r.phone || '').replace(/[\s-]/g, '');
    if (blank(r.farmName) || String(r.farmName).trim().length > 100) return respond(400, { success: false, error: '請填寫農場名稱（100 字以內）' });
    if (blank(r.contactName) || String(r.contactName).trim().length > 50) return respond(400, { success: false, error: '請填寫聯絡人姓名（50 字以內）' });
    if (!/^0\d{8,9}$/.test(tel)) return respond(400, { success: false, error: '請填寫正確的聯絡電話（手機 09 開頭 10 碼，市話含區碼）' });
    if (blank(r.address) || String(r.address).trim().length > 255) return respond(400, { success: false, error: '請填寫農場地址或地號（255 字以內）' });
    if (method === 'POST' && list.length >= 20) return respond(409, { success: false, error: '每個帳號最多登錄 20 個農場' });
    var data = {
      id: method === 'POST' ? nextId(list, 'FM') : id, farmName: String(r.farmName).trim(), contactName: String(r.contactName).trim(),
      phone: tel, address: String(r.address).trim(), farmType: blank(r.farmType) ? '個人農戶' : String(r.farmType).trim()
    };
    if (method === 'POST') list.push(data); else list[idx] = data;
    put(k, { s: 200, b: { success: true, data: list } });
    return respond(method === 'POST' ? 201 : 200, { success: true, data: data, message: method === 'POST' ? '農場已登錄' : '農場資料已儲存' });
  }
  // 指名回頭工人（/farmers/me/past-workers）：名單取錄製資料，能否排班依目前訂單推算（同後端 PreferredWorkerService，
  // 只看指名佔用與出工名單；保險、證件等資格以錄製當時為準）
  var ACTIVE_ORDER = ['pending', 'confirmed', 'in_progress'];
  function pastWorkerList() {
    var u = currentUser();
    if (!u || u.role !== 'farmer') return null;
    var e = get('GET /farmers/me/past-workers');
    return (e && e.b && e.b.data) || [];
  }
  function workerBusy(workerId, start, end, slot) {
    return (listOf(get('GET /orders')) || []).some(function (o) {
      if (ACTIVE_ORDER.indexOf(o.status) < 0 || !o.startDate || !o.endDate || o.startDate > end || o.endDate < start) return false;
      if (!slotsOverlap(slotOf(o), normSlot(slot))) return false;   // 同一天上午、下午不衝突
      var m = get('GET /matching/orders/' + encodeURIComponent(o.id));
      var crew = (m && m.b && m.b.data && m.b.data.order && m.b.data.order.crew) || [];
      return crew.length ? crew.indexOf(workerId) >= 0 : o.preferredWorkerId === workerId;
    });
  }
  function pastWorkersApi(query) {
    var list = pastWorkerList();
    if (!list) return respond(403, { success: false, error: 'FORBIDDEN' });
    var sq = /[?&]start=([^&]*)/.exec(query || ''), eq = /[?&]end=([^&]*)/.exec(query || '');
    var start = sq ? decodeURIComponent(sq[1]) : '', end = eq ? decodeURIComponent(eq[1]) : start;
    var tq = /[?&]timeSlot=([^&]*)/.exec(query || ''), slot;
    try { slot = normSlot(tq ? decodeURIComponent(tq[1]) : null); } catch (err) { return respond(400, { success: false, error: err.message }); }
    return respond(200, { success: true, data: list.map(function (w) {
      var busy = start ? workerBusy(w.workerId, start, end, slot) : null;
      return Object.assign({}, w, { available: start ? !busy : null, unavailableReason: busy ? '該期間已有排班' : null });
    }) });
  }
  function findIn(listKey, id) {
    var list = listOf(get(listKey)) || [];
    for (var i = 0; i < list.length; i++) if (String(list[i].id) === String(id)) return list[i];
    return null;
  }
  // 每日時段（同後端 Order／OrderService）：整天 8 小時；上午 07–11、下午 13–17 各 4 小時，半日最低計費每人每天 4 小時
  var SLOTS = { full_day: '整天', morning: '上午 07:00–11:00', afternoon: '下午 13:00–17:00' };
  function normSlot(v) {
    if (blank(v)) return 'full_day';
    if (!SLOTS[String(v).trim()]) throw new Error('時段 (timeSlot) 必須為 full_day / morning / afternoon');
    return String(v).trim();
  }
  function slotOf(o) { return blank(o.timeSlot) ? 'full_day' : o.timeSlot; }
  function slotsOverlap(a, b) { return a === 'full_day' || b === 'full_day' || a === b; }
  function calcOrder(cropId, area, plan, zone, cat, reqWorkers, slot) {
    slot = normSlot(slot);
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
    var workHours = totalHours, estDays;
    if (slot === 'full_day') {
      estDays = Math.round((totalHours / (workers * 8)) * 10) / 10;
    } else {
      estDays = Math.ceil(totalHours / (workers * 4));
      totalHours = workers * estDays * 4;   // 計費工時（最低計費：每人每天 4 小時）
    }
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
      workHours: workHours, minimumTopUpHours: totalHours - workHours, timeSlot: slot, timeSlotLabel: SLOTS[slot],
      hoursPerDay: slot === 'full_day' ? 8 : 4,
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
    var slot = normSlot(req.timeSlot);
    if (!blank(req.packageId)) {
      if (slot !== 'full_day') throw new Error('工時包為整天班，不提供半日時段；半日請改用一般預約');
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
        workHours: b.hours, minimumTopUpHours: 0,
        unitRate: Math.round(b.discountPrice / b.hours), standardTotal: b.originalPrice + d.transportSurcharge,
        actualTotal: b.discountPrice + d.transportSurcharge, deposit: b.depositAmount,
        savings: Math.max(0, b.originalPrice - b.discountPrice)
      });
    }
    var area = req.area != null ? parseFloat(req.area) : 5;
    return calcOrder(blank(req.cropId) ? 'taoyuan_veg' : req.cropId, area, req.plan, zone, req.dispatchCategory, reqWorkers, slot);
  }
  function createOrder(o) {
    if (blank(o.farmerName)) throw new Error('農場主姓名為必填');
    var u = currentUser() || {};
    var calc = calculate({
      cropId: o.cropId, area: o.area, plan: o.plan, zone: o.transportZone,
      dispatchCategory: o.dispatchCategory, workers: o.requestedWorkers, packageId: o.packageId, timeSlot: o.timeSlot
    });
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    var now = new Date();
    var order = Object.assign({}, o, {
      farmerId: o.farmerId || (u.role === 'farmer' ? u.refId : null),
      plan: calc.plan, transportZone: calc.transportZone, dispatchCategory: calc.dispatchCategory, timeSlot: normSlot(o.timeSlot),
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
    // 選的農場：須為登入農場主自己的農場，名稱存快照（同後端 OrderService.createOrder）
    var farm = null;
    if (!blank(o.farmId)) {
      var fk = farmKey();
      farm = fk ? farmList(fk).filter(function (f) { return f.id === o.farmId; })[0] : null;
      if (!farm) throw new Error('找不到選擇的農場，請重新選擇');
    }
    order.farmId = farm ? farm.id : null;
    order.farmName = farm ? farm.farmName : null;
    // 指名回頭工人：限合作過的工人、須有工期且未被佔用；姓名與工班由名單帶入（同後端 PreferredWorkerService）
    order.preferredWorkerName = null;
    if (blank(o.preferredWorkerId)) {
      order.preferredWorkerId = null;
    } else {
      var pw = (pastWorkerList() || []).filter(function (w) { return w.workerId === o.preferredWorkerId; })[0];
      if (!pw) throw new Error('只能指名曾到您農場出工的工人');
      if (!o.startDate || !o.endDate) throw new Error('指名工人須先選起工日期');
      if (workerBusy(pw.workerId, o.startDate, o.endDate, order.timeSlot)) {
        throw new Error(pw.name + ' 在 ' + o.startDate + ' 起的工期' + (order.timeSlot === 'full_day' ? '' : '（' + SLOTS[order.timeSlot] + '）') + '已有排班，請改期、改時段或不指名');
      }
      order.preferredWorkerName = pw.name;
      if (pw.teamId) order.assignedTeamId = pw.teamId;   // 農場主不能自選工班，交給指名工人的工班
    }
    var e = get('GET /orders') || { s: 200, b: { success: true, data: [] } };
    var list = listOf(e);
    order.id = nextId(list.filter(function (x) { return /^ORD-\d{4}-\d+$/.test(x.id); }), 'ORD-' + now.getFullYear() + '-');
    list.push(order);
    put('GET /orders', e);
    put('GET /orders/' + encodeURIComponent(order.id), { s: 200, b: { success: true, data: order } });
    return order;
  }

  // ---------------- 訂單修改與狀態機（移植 OrderService.updateOrder / changeStatus） ----------------
  var TRANSITIONS = {
    pending: ['confirmed', 'cancelled'], confirmed: ['in_progress', 'cancelled'],
    in_progress: ['completed', 'cancelled'], completed: [], cancelled: []
  };
  function orderRef(id) {
    var dk = 'GET /orders/' + encodeURIComponent(id), d = get(dk);
    var o = (d && d.b && d.b.data) || findIn('GET /orders', id);
    return o ? { key: dk, order: clone(o) } : null;
  }
  function saveOrder(ref) {
    var o = ref.order;
    patchList('GET /orders', String(o.id), o);
    put(ref.key, { s: 200, b: { success: true, data: o } });
    syncMatchingOrder(o.id, o);
  }
  function updateOrder(id, u) {
    var ref = orderRef(id);
    if (!ref) return respond(404, { success: false, error: '找不到該訂單編號: ' + id });
    var o = ref.order;
    if (o.status === 'cancelled' || o.status === 'completed') return respond(409, { success: false, error: '已取消或已完工的訂單不可修改' });
    if (u.area != null && (u.area < 0 || isNaN(u.area))) return respond(400, { success: false, error: '面積必須大於 0' });
    var priced = false;
    if (u.cropId != null && u.cropId !== o.cropId) { o.cropId = u.cropId; priced = true; }
    if (u.area > 0 && +u.area !== +o.area) { o.area = +u.area; priced = true; }
    ['plan', 'transportZone', 'dispatchCategory'].forEach(function (f) {
      if (u[f] != null && u[f] !== o[f]) { o[f] = u[f]; priced = true; }
    });
    if (u.timeSlot != null) {
      var ns;
      try { ns = normSlot(u.timeSlot); } catch (err) { return respond(400, { success: false, error: err.message }); }
      if (ns !== slotOf(o)) {
        var me = currentUser() || {};
        if (me.role === 'farmer' && o.status !== 'pending') return respond(409, { success: false, error: '訂單已排定，時段調整請聯絡合作社（可能影響已安排的出工名單）' });
        o.timeSlot = ns; priced = true;
      }
    }
    if (priced && o.depositPaid) return respond(409, { success: false, error: '已付定金的訂單不可變更作物、面積、方案或時段，請聯繫調度中心' });
    if (priced && o.packageId) o.packageId = null;
    if (u.requestedWorkers != null) o.requestedWorkers = u.requestedWorkers;
    if (!blank(u.farmerName)) o.farmerName = u.farmerName;
    ['phone', 'farmLocation', 'foremanName', 'foremanPhone', 'foremanNotes', 'assignedTeamId'].forEach(function (f) {
      if (u[f] != null) o[f] = u[f];
    });
    if (u.startDate != null || u.endDate != null) {
      var s = u.startDate != null ? u.startDate : o.startDate, e = u.endDate != null ? u.endDate : o.endDate;
      if (s && e && e < s) return respond(400, { success: false, error: '完工日不可早於開工日' });
      o.startDate = s; o.endDate = e;
    }
    if (priced) {
      var c;
      try {
        c = calculate({ cropId: o.cropId, area: o.area, plan: o.plan, zone: o.transportZone, dispatchCategory: o.dispatchCategory, workers: o.requestedWorkers, timeSlot: o.timeSlot });
      } catch (err) { return respond(400, { success: false, error: err.message }); }
      Object.assign(o, {
        cropName: c.cropName, planName: c.planName, categoryName: c.categoryName, totalHours: c.totalHours,
        suggestedWorkers: c.suggestedWorkers, unitRate: c.unitRate, standardAmount: c.standardTotal,
        finalAmount: c.actualTotal, depositAmount: c.deposit, transportSurcharge: c.transportSurcharge
      });
      if (o.status === 'pending' && o.depositAmount <= 0) o.status = 'confirmed';
      else if (o.status === 'confirmed' && o.depositAmount > 0) o.status = 'pending';
    }
    saveOrder(ref);
    return respond(200, { success: true, data: o, message: '訂單已更新' });
  }
  function changeStatus(id, next) {
    var ref = orderRef(id);
    if (!ref) return respond(404, { success: false, error: '找不到該訂單編號: ' + id });
    var o = ref.order;
    if (!TRANSITIONS[next]) return respond(400, { success: false, error: 'status 必須為 pending / confirmed / in_progress / completed / cancelled' });
    if (next === o.status) return respond(200, { success: true, data: o });
    if ((TRANSITIONS[o.status] || []).indexOf(next) < 0) return respond(409, { success: false, error: '訂單狀態不可由 ' + o.status + ' 變更為 ' + next });
    if (next === 'confirmed' && o.depositAmount > 0 && !o.depositPaid) return respond(409, { success: false, error: '訂單尚未收到定金，無法轉為已排定' });
    if (next === 'in_progress') {
      // 派工前加保檢查：有出工名單看名單，否則看分派工班每位成員
      var m = get('GET /matching/orders/' + encodeURIComponent(id));
      var crew = (m && m.b && m.b.data && m.b.data.order && m.b.data.order.crew) || [];
      var problems = (listOf(get('GET /insurance/workers')) || []).filter(function (w) {
        return (crew.length ? crew.indexOf(w.workerId) >= 0 : w.teamId === o.assignedTeamId) && !w.fullyInsured;
      }).map(function (w) {
        return w.name + '（缺' + (w.missing || []).map(function (t) { return INS_LABEL[t] || t; }).join('、') + '）';
      });
      if (problems.length) return respond(409, { success: false, error: '工班有成員尚未完整加保，不可出工：' + problems.join('；') });
    }
    o.status = next;
    saveOrder(ref);
    return respond(200, { success: true, data: o });
  }
  // 農場主取消自己的訂單（移植 OrderService.cancelOrder）：保留紀錄、狀態改 cancelled；施工中以後須聯繫調度中心
  function cancelOrder(id) {
    var ref = orderRef(id);
    if (!ref) return respond(404, { success: false, error: '找不到該訂單編號: ' + id });
    var o = ref.order, me = currentUser() || {};
    if (o.farmerId && me.refId && o.farmerId !== me.refId) return respond(403, { success: false, error: '無權限操作此訂單' });
    if (o.status !== 'cancelled') {
      if (o.status !== 'pending' && o.status !== 'confirmed') {
        return respond(409, { success: false, error: '此訂單目前狀態 (' + o.status + ') 無法自行取消，請聯繫調度中心' });
      }
      o.status = 'cancelled';
      saveOrder(ref);
    }
    return respond(200, { success: true, data: o, message: '成功取消訂單: ' + id });
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
    // 付款紀錄（GET /payments/{id}/status）：訂單頁「下載發票」從這裡讀發票號碼與付款時間
    var sKey = 'GET /payments/' + encodeURIComponent(order.id) + '/status', se = get(sKey);
    var sd = (se && se.b && se.b.data) || {
      orderId: order.id, farmerName: order.farmerName, finalAmount: order.finalAmount,
      depositAmount: order.depositAmount, depositRequired: true, transactions: []
    };
    var txs = sd.transactions || [];
    var now = new Date(), stamp = now.getTime().toString().slice(-8);
    var local = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
    var tx = txs.filter(function (t) { return t.status === 'PAID'; })[0];
    if (!tx) {
      tx = {
        id: 'TX-MOCK-' + stamp, orderId: order.id, amount: order.depositAmount, provider: 'mock',
        paymentMethod: b.paymentMethod || 'credit_card', status: 'PAID', payerName: order.farmerName,
        authCode: stamp.slice(-6), invoiceNo: 'AG-MOCK-' + stamp.slice(-6), createdAt: local, paidAt: local
      };
      txs.unshift(tx);
    }
    sd.transactions = txs;
    sd.depositPaid = true;
    sd.orderStatus = patch.status;
    put(sKey, { s: 200, b: { success: true, data: sd } });
    return respond(200, {
      success: true, transactionId: tx.id, orderId: order.id, farmerName: order.farmerName,
      provider: 'mock', paymentMethod: tx.paymentMethod, amountPaid: tx.amount,
      currency: 'TWD', status: 'SUCCESS', authCode: tx.authCode, invoiceNo: tx.invoiceNo,
      paymentTime: String(tx.paidAt).replace('T', ' '), orderStatus: patch.status, alreadyPaid: already,
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

  // ---------------- LINE 帳號綁定（移植 LineBindingService.bind / unbind / list） ----------------
  function lineStore() {
    var key = 'GET /line/bindings', e = get(key) || { s: 200, b: { success: true, bindings: [] } };
    e.b.bindings = e.b.bindings || [];
    return { key: key, entry: e, list: e.b.bindings };
  }
  function lineBind(b) {
    var u = currentUser();
    if (!u) return respond(401, { success: false, error: '請先登入平台帳號' });
    var ch = b.channel === 'worker' ? 'worker' : 'farmer';
    if (ch === 'farmer' && u.role !== 'farmer') return respond(409, { success: false, error: '此 LINE 帳號（智農派工（農場主））只提供農場主綁定；工作人員請加入「智農派工（工作者）」帳號' });
    if (ch === 'worker' && !/^worker_/.test(u.role || '')) return respond(409, { success: false, error: '此 LINE 帳號（智農派工（工作者））只提供工作人員綁定；農場主請加入「智農派工（農場主）」帳號' });
    var lineId = String(b.idToken || '').replace(/^mock:/, '');
    if (!/^U[0-9a-f]{32}$/.test(lineId)) return respond(400, { success: false, error: 'LINE 身分驗證失敗' });
    var masked = lineId.slice(0, 6) + '****';
    var st = lineStore(), mine = null, clash = null;
    st.list.forEach(function (x) {
      if (x.channel !== ch) return;
      if (x.username === u.username) mine = x;
      else if (x.bound && x._lineUserId === lineId) clash = x;
    });
    if (clash) return respond(409, { success: false, error: '這個 LINE 帳號已綁定其他平台帳號，請先解除原本的綁定' });
    if (mine && mine.bound && mine._lineUserId !== lineId) return respond(409, { success: false, error: '您的平台帳號已綁定另一個 LINE 帳號，請先解除原本的綁定' });
    if (!mine) {
      mine = { id: 'LU-' + String(st.list.length + 1).padStart(4, '0'), channel: ch, username: u.username, role: u.role, refId: u.refId || null };
      st.list.push(mine);
    }
    var nat = u.role === 'worker_foreign' ? ((findIn('GET /workers', u.refId) || {}).nationality || '') : '';
    Object.assign(mine, {
      _lineUserId: lineId, lineUserId: masked, displayName: u.name || u.username, bound: true,
      lang: mine.langLocked ? mine.lang : ({ '越南': 'vi', '印尼': 'id' }[nat] || 'zh'),
      langLocked: !!mine.langLocked, followed: true, boundAt: localStamp(new Date())
    });
    put(st.key, st.entry);
    return respond(200, { success: true, channel: ch, displayName: mine.displayName, lang: mine.lang });
  }
  function lineUnbind(match) {
    var st = lineStore(), hit = null;
    st.list.forEach(function (x) { if (!hit && match(x)) hit = x; });
    if (!hit) return respond(404, { success: false, error: '查無綁定紀錄' });
    hit.bound = false;
    hit.boundAt = null;
    put(st.key, st.entry);
    return respond(200, { success: true });
  }

  // ---------------- 出勤打卡（移植 AttendanceService.clockIn / clockOut、Attendance.completeShift） ----------------
  function localStamp(d) {
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' +
      pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }
  function completeShift(a, out) {
    a.clockOutTime = localStamp(out);
    a.status = 'completed';
    var minutes = Math.max(0, Math.round((out - new Date(a.clockInTime)) / 60000));
    a.actualHours = Math.round(minutes / 6) / 10;
    a.normalHours = Math.min(a.actualHours, 8);
    a.overtimeHours = Math.round(Math.max(0, a.actualHours - 8) * 10) / 10;
  }
  // 錄製資料裡沒有出勤紀錄、但工作台預設「作業中」的示範工人 → 打卡地點（找不到工班訂單時用）
  var DEMO_ON_SHIFT = { W012: '雲林縣元長鄉設施彩椒溫室專案區' };

  // 工人所屬工班目前的訂單地點（規則同工作台任務卡、LINE：履約中優先，其次已排定、待付定金）
  var TASK_RANK = { in_progress: 0, confirmed: 1, pending: 2 };
  function teamTaskLocation(workerId) {
    var w = findIn('GET /workers', workerId);
    if (!w || !w.teamId) return null;
    var o = (listOf(get('GET /orders')) || [])
      .filter(function (x) { return x.assignedTeamId === w.teamId && TASK_RANK[x.status] != null; })
      .sort(function (a, b) { return TASK_RANK[a.status] - TASK_RANK[b.status] || String(b.id).localeCompare(String(a.id)); })[0];
    return o ? (o.farmLocation || null) : null;
  }

  // 錄製資料裡還沒下班的紀錄停在錄製日；改成「8 小時前上班」，
  // 對應工人頁 demo 預設「已上班、可直接下班打卡」，下班時工時約 8 小時、不會累積好幾天
  function attendanceStore(workerId) {
    var hk = 'GET /attendance/history/' + encodeURIComponent(workerId);
    var he = get(hk) || { s: 200, b: { success: true, workerId: workerId, totalRecords: 0, data: [] } };
    var st = { key: hk, entry: he, list: he.b.data || (he.b.data = []) };
    var today = localStamp(new Date()).slice(0, 10), moved = false;
    // 錄製資料沒有出勤紀錄的示範工人：今天還沒任何紀錄時補一筆「已上班」，工作台一打開就能直接下班打卡
    var seed = DEMO_ON_SHIFT[workerId] && (teamTaskLocation(workerId) || DEMO_ON_SHIFT[workerId]);
    if (seed && !st.list.some(function (a) { return a.workDate === today || !a.clockOutTime; })) {
      var maxId = st.list.reduce(function (m, a) { return Math.max(m, +a.id || 0); }, 0);
      st.list.unshift({
        id: maxId + 1, workerId: workerId, workDate: today, clockInTime: '', clockOutTime: null,
        location: seed, gpsAccuracyMeters: null, status: 'working', actualHours: null, normalHours: null,
        overtimeHours: null, foremanSignature: null, orderId: null, foremanRating: null, foremanRatedBy: null,
        foremanRatedAt: null, createdAt: localStamp(new Date()), _demoShift: true
      });
    }
    st.list.forEach(function (a) {
      if (a.clockOutTime) return;
      if (a._demoShift || (a.workDate && a.workDate < today)) {
        // 上班時間固定為「現在往前 8 小時」，下班前每次讀取都重算，路演幾點按下班都約 8 小時；
        // 地點改成工班目前訂單的地點，和工作台任務卡一致
        if (!a._demoShift) a.location = teamTaskLocation(workerId) || a.location;
        a.clockInTime = localStamp(new Date(Date.now() - 8 * 3600e3));
        a.workDate = today;
        a._demoShift = true;
        moved = true;
      }
    });
    if (moved) saveAttendance(workerId, st);
    return st;
  }
  function saveAttendance(workerId, st) {
    st.entry.b.totalRecords = st.list.length;
    put(st.key, st.entry);
    var today = localStamp(new Date()).slice(0, 10);
    put('GET /attendance/today/' + encodeURIComponent(workerId), {
      s: 200, b: { success: true, data: st.list.filter(function (a) { return a.workDate === today; }) }
    });
  }
  function clock(type, b) {
    var workerId = b.workerId;
    if (blank(workerId)) return respond(400, { success: false, error: '農工工號 (workerId) 不得為空！' });
    if (!findIn('GET /workers', workerId)) return respond(404, { success: false, error: '找不到該農工編號：' + workerId });
    var st = attendanceStore(workerId), now = new Date(), today = localStamp(now).slice(0, 10);
    var open = st.list.filter(function (a) { return !a.clockOutTime; })[0];
    if (type === 'clock-in') {
      if (open) return respond(409, { success: false, error: '已有未下班的打卡紀錄（' + open.workDate + '），請先完成下班打卡' });
      var maxId = st.list.reduce(function (m, a) { return Math.max(m, +a.id || 0); }, 0);
      var att = {
        id: maxId + 1, workerId: workerId, workDate: today, clockInTime: localStamp(now), clockOutTime: null,
        location: b.location || '未提供 GPS 位置描述', gpsAccuracyMeters: null, status: 'working',
        actualHours: null, normalHours: null, overtimeHours: null, foremanSignature: null, orderId: null,
        foremanRating: null, foremanRatedBy: null, foremanRatedAt: null, createdAt: localStamp(now)
      };
      st.list.unshift(att);
      saveAttendance(workerId, st);
      return respond(200, { success: true, data: att, message: '📍 上班 GPS 打卡成功！位置：' + att.location });
    }
    if (!open) return respond(409, { success: false, error: '找不到尚未下班的上班打卡紀錄，請先上班打卡' });
    completeShift(open, now);
    open.foremanSignature = b.foremanSignature || null;
    saveAttendance(workerId, st);
    return respond(200, {
      success: true, data: open, workHours: open.actualHours,
      message: '🏁 下班打卡與工頭簽核完成！本日工時：' + open.actualHours + ' 小時 (含加班 ' + open.overtimeHours + ' 小時)'
    });
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
    var crewIds = b.crew.join(',');
    patchList('GET /orders', orderId, { assignedTeamId: b.teamId, crewWorkerIds: crewIds });
    var od = get('GET /orders/' + enc);
    if (od && od.b && od.b.data) { od.b.data.assignedTeamId = b.teamId; od.b.data.crewWorkerIds = crewIds; put('GET /orders/' + enc, od); }
    return respond(200, { success: true, data: rec });
  }

  // ---------------- 本工班名冊（移植 WorkerScheduleService.teamRosterOf） ----------------
  function teamRoster(workerId) {
    var w = findIn('GET /workers', workerId);
    if (!w) return respond(404, { success: false, error: '找不到該農工編號：' + workerId });
    var members = !w.teamId ? [] : (listOf(get('GET /workers')) || [])
      .filter(function (x) { return x.teamId === w.teamId && x.id !== w.id; })
      .map(function (x) {
        return { id: x.id, name: x.name, chineseName: x.chineseName, nationality: x.nationality,
          tier: x.tier, employmentType: x.employmentType || null, leader: !!x.leader };
      })
      .sort(function (a, b) { return String(a.id).localeCompare(String(b.id)); });
    var t = (listOf(get('GET /dispatch/teams')) || []).filter(function (x) { return x.id === w.teamId; })[0];
    return respond(200, { success: true, data: { workerId: w.id, teamId: w.teamId || null, teamName: t ? t.name : null, members: members } });
  }

  // ---------------- 工人本人排班（移植 WorkerScheduleService.scheduleOf） ----------------
  // 由目前的訂單（含示範時新下的單、媒合結果）即時算出，不另外錄製
  function workerSchedule(workerId, query) {
    var w = findIn('GET /workers', workerId);
    if (!w) return respond(404, { success: false, error: '找不到該農工編號：' + workerId });
    var q = function (k) { var m = new RegExp('[?&]' + k + '=([^&]*)').exec(query || ''); return m ? decodeURIComponent(m[1]) : ''; };
    var from = q('from'), to = q('to');
    var bad = function (v) {
      if (!v) return false;
      if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return true;
      var d = new Date(v + 'T00:00:00');
      return isNaN(d) || d.getMonth() + 1 !== Number(v.slice(5, 7)) || d.getDate() !== Number(v.slice(8));
    };
    if (bad(from) || bad(to)) return respond(400, { success: false, error: 'from / to 須為 YYYY-MM-DD 日期' });
    if (from && to && to < from) return respond(400, { success: false, error: 'to 不可早於 from' });
    var teams = listOf(get('GET /dispatch/teams')) || [];
    var teamOf = function (id) { return teams.filter(function (t) { return t.id === id; })[0] || null; };
    var items = [];
    (listOf(get('GET /orders')) || []).forEach(function (o) {
      if (o.status === 'cancelled' || !o.startDate) return;
      var crew = String(o.crewWorkerIds || '').split(',').map(function (x) { return x.trim(); }).filter(Boolean);
      var via = crew.length ? (crew.indexOf(workerId) >= 0 ? 'crew' : null) : (w.teamId && o.assignedTeamId === w.teamId ? 'team' : null);
      if (!via) return;
      var end = o.endDate || o.startDate;
      if ((from && end < from) || (to && o.startDate > to)) return;
      var t = teamOf(o.assignedTeamId) || {};
      items.push({
        orderId: o.id, status: o.status, statusLabel: STATUS_LABEL[o.status] || o.status,
        startDate: o.startDate, endDate: end, assignedVia: via, teamId: o.assignedTeamId,
        timeSlot: slotOf(o), timeSlotLabel: SLOTS[slotOf(o)], preferredByFarmer: o.preferredWorkerId === workerId,
        cropName: o.cropName, area: o.area, categoryName: o.categoryName, farmLocation: o.farmLocation,
        farmerName: o.farmerName, foremanName: o.foremanName || t.leader || null,
        foremanPhone: o.foremanPhone || t.leaderPhone || null, foremanNotes: o.foremanNotes || null,
        vehicle: t.vehicle || null, driver: t.driver || null, hedgeLocation: t.hedgeLocation || null, hedgeTask: t.hedgeTask || null
      });
    });
    items.sort(function (a, b) { return a.startDate < b.startDate ? -1 : a.startDate > b.startDate ? 1 : String(a.orderId).localeCompare(String(b.orderId)); });
    var mt = teamOf(w.teamId);
    return respond(200, { success: true, data: {
      workerId: w.id, teamId: w.teamId || null,
      team: mt ? { id: mt.id, name: mt.name, leader: mt.leader, leaderPhone: mt.leaderPhone, vehicle: mt.vehicle, driver: mt.driver,
        normalLocation: mt.normalLocation, normalTask: mt.normalTask, hedgeLocation: mt.hedgeLocation, hedgeTask: mt.hedgeTask } : null,
      from: from || null, to: to || null, items: items
    } });
  }
  // 新訂單（示範時剛下的單）沒有錄製的媒合建議：拿同派工類別的既有訂單當範本（技能需求、工班排序相同），
  // 換上新訂單的資料；出工名單依新訂單人數重新挑選建議人選
  var STATUS_LABEL = { pending: '待付定金', confirmed: '已排定', in_progress: '履約中', completed: '已完工', cancelled: '已取消' };
  function matchingTemplate(o) {
    var pool = (listOf(get('GET /orders')) || []).filter(function (x) {
      return x.id !== o.id && FIX['GET /matching/orders/' + encodeURIComponent(x.id)];
    });
    return pool.filter(function (x) { return x.dispatchCategory === o.dispatchCategory; })[0] || pool[0] || null;
  }
  function synthMatching(orderId) {
    var o = orderRef(orderId);
    o = o && o.order;
    var t = o && matchingTemplate(o);
    if (!t) return null;
    var tm = clone(FIX['GET /matching/orders/' + encodeURIComponent(t.id)].b.data);
    var team = o.assignedTeamId && o.assignedTeamId !== '待分派' ? o.assignedTeamId : null;
    var rec = {
      order: Object.assign({}, tm.order, {
        orderId: o.id, status: o.status, statusLabel: STATUS_LABEL[o.status] || o.status, farmerName: o.farmerName,
        cropName: o.cropName, categoryName: o.categoryName, suggestedWorkers: o.suggestedWorkers,
        startDate: o.startDate || null, endDate: o.endDate || null, assignedTeamId: team, crew: [],
        timeSlot: slotOf(o), timeSlotLabel: SLOTS[slotOf(o)],
        preferredWorkerId: o.preferredWorkerId || null, preferredWorkerName: o.preferredWorkerName || null
      }),
      teams: tm.teams, crew: null
    };
    var e = { s: 200, b: { success: true, data: rec } };
    put('GET /matching/orders/' + encodeURIComponent(o.id), e);
    return e;
  }
  function synthCrew(orderId, teamId) {
    var o = orderRef(orderId);
    o = o && o.order;
    var t = o && matchingTemplate(o);
    var src = t && get('GET /matching/orders/' + encodeURIComponent(t.id) + '/crew?teamId=' + encodeURIComponent(teamId));
    if (!src || !src.b || !src.b.data) return null;
    var c = clone(src.b.data), need = o.suggestedWorkers || c.needed, picked = 0;
    c.needed = need;
    c.currentCrew = [];
    (c.candidates || []).forEach(function (x) {
      x.onCurrentCrew = false;
      x.recommended = !x.suspended && picked < need;
      if (x.recommended) picked++;
    });
    c.shortage = Math.max(0, need - picked);
    var e = { s: 200, b: { success: true, data: c } };
    put('GET /matching/orders/' + encodeURIComponent(o.id) + '/crew?teamId=' + encodeURIComponent(teamId), e);
    return e;
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

    // 農場主自助註冊：檢查與後端相同的必填欄位，回一個示範農場主身分（不存帳密）
    if (method === 'POST' && sp.path === '/auth/register') {
      var r = body || {}, phone = String(r.phone || '').replace(/[\s-]/g, '');
      if (!r.name || !/^09\d{8}$/.test(phone) || !r.password || String(r.password).length < 8 || !r.address || r.pdpaConsent !== true) {
        return respond(400, { success: false, error: '請確認姓名、手機、密碼（至少 8 字元）、農地地址與個資同意都已填寫' });
      }
      return respond(200, { success: true, token: 'mock.farmer.F900', user: {
        username: phone, name: String(r.name), role: 'farmer', refId: 'F900',
        roleDesc: (r.farmName || '自助註冊') + ' 農場主' } });
    }

    var fm = /^\/farmers\/me\/farms(?:\/([^/]+))?$/.exec(sp.path);
    if (fm) return farmApi(method, fm[1] && decodeURIComponent(fm[1]), body);

    // 有業務邏輯、會影響後續查詢的動作
    if (method === 'POST' && sp.path === '/orders/calculate') {
      try { return respond(200, { success: true, data: calculate(body || {}) }); } catch (err) { return respond(400, { success: false, error: err.message }); }
    }
    if (method === 'POST' && sp.path === '/orders') {
      try { return respond(200, { success: true, data: createOrder(body || {}) }); } catch (err) { return respond(400, { success: false, error: err.message }); }
    }
    if (method === 'POST' && sp.path === '/payments/checkout') return checkout(body || {});
    if (method === 'POST' && (sp.path === '/attendance/clock-in' || sp.path === '/attendance/clock-out')) return clock(seg[1], body || {});
    if (method === 'POST' && sp.path === '/line/bind') return lineBind(body || {});
    if (method === 'POST' && sp.path === '/line/notify/tomorrow') {
      // 明日出工通知：工期涵蓋明天、已排定或履約中的訂單，通知其出工名單（沒有名單就以建議人數計）
      var tm = new Date(); tm.setDate(tm.getDate() + 1);
      var tmr = localStamp(tm).slice(0, 10), people = 0;
      var due = (listOf(get('GET /orders')) || []).filter(function (o) {
        return (o.status === 'confirmed' || o.status === 'in_progress') && o.startDate && o.startDate <= tmr && (!o.endDate || o.endDate >= tmr);
      });
      due.forEach(function (o) {
        var m = get('GET /matching/orders/' + encodeURIComponent(o.id));
        var crew = m && m.b && m.b.data && m.b.data.order && m.b.data.order.crew;
        people += (crew && crew.length) || o.suggestedWorkers || 0;
      });
      return respond(200, { success: true, date: tmr, orders: due.length, workerRecipients: people, skippedQuietHours: 0 });
    }
    if (method === 'POST' && sp.path === '/line/notify/farmer-broadcast') {
      if (blank(body && body.text)) return respond(400, { success: false, error: '請輸入通知內容' });
      var farmers = (lineStore().list || []).filter(function (x) { return x.channel === 'farmer' && x.bound; }).length;
      return respond(200, { success: true, recipients: farmers });
    }
    if (method === 'DELETE' && /^\/line\/bind\/[^/]+$/.test(sp.path)) {
      var me = currentUser() || {};
      return lineUnbind(function (x) { return x.channel === seg[2] && x.username === me.username && x.bound; });
    }
    if (method === 'DELETE' && /^\/line\/bindings\/[^/]+$/.test(sp.path)) {
      var bid = decodeURIComponent(seg[2]);
      return lineUnbind(function (x) { return x.id === bid; });
    }
    if (method === 'PUT' && /^\/evaluations\/attendance\/[^/]+$/.test(sp.path)) return rateShift(decodeURIComponent(seg[2]), body || {});
    if (method === 'PUT' && /^\/matching\/orders\/[^/]+$/.test(sp.path)) return saveCrew(decodeURIComponent(seg[2]), body || {});
    if (method === 'DELETE' && /^\/orders\/[^/]+$/.test(sp.path) && (currentUser() || {}).role === 'farmer') return cancelOrder(decodeURIComponent(seg[1]));
    if (method === 'PUT' && /^\/orders\/[^/]+$/.test(sp.path)) return updateOrder(decodeURIComponent(seg[1]), body || {});
    if (method === 'PUT' && /^\/orders\/[^/]+\/status$/.test(sp.path)) return changeStatus(decodeURIComponent(seg[1]), (body || {}).status);
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
      var names = { sunny: '全台晴朗炎熱', cloudy: '舒適多雲時晴', shower: '局部短暫陣雨', heavy_rain: '滯留鋒面大暴雨', typhoon: '強烈颱風警戒', cold_wave: '強烈寒流特報' };
      var sb = sr ? clone(sr.b) : { success: true };
      sb.message = '已成功切換天候情境：' + (names[sid] || sid);   // 後端訊息帶英文代碼，路演畫面改顯示中文
      return respond(200, sb);
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
      if (sp.path === '/farmers/me/farms') return farmApi('GET');
      if (sp.path === '/farmers/me/past-workers') return pastWorkersApi(sp.query);
      if (sp.path === '/line/config') {
        // 錄製時 LINE Bot 沒開；純前端示範的對話由頁面模擬，狀態顯示「模擬中」。金鑰等正式設定照實呈現。
        var lc = findGet(p) || { s: 200, b: { success: true } };
        lc.b.linebot = Object.assign({}, lc.b.linebot, { reachable: true, channels: { farmer: 'MOCK', worker: 'MOCK' } });
        return respond(200, lc.b);
      }
      if (sp.path === '/line/bindings') {
        var ls = lineStore();
        return respond(200, Object.assign({}, ls.entry.b, {
          bindings: ls.list.map(function (x) { var c = clone(x); delete c._lineUserId; return c; })
        }));
      }
      // 工人本月出勤統計：由出勤紀錄即時加總（錄製的統計不會隨打卡更新）
      var wm = /^\/workers\/([^/]+)\/attendance$/.exec(sp.path);
      if (wm) {
        var wst = attendanceStore(decodeURIComponent(wm[1]));
        var yq = /[?&]year=(\d+)/.exec(sp.query), mq = /[?&]month=(\d+)/.exec(sp.query);
        var ym = yq && mq ? yq[1] + '-' + ('0' + mq[1]).slice(-2) : null;
        var recs = wst.list.filter(function (a) { return !ym || String(a.workDate).slice(0, 7) === ym; });
        var doneRecs = recs.filter(function (a) { return a.clockOutTime && a.actualHours != null; });
        var sum = function (f) { return Math.round(doneRecs.reduce(function (t, a) { return t + (+a[f] || 0); }, 0) * 10) / 10; };
        var days = {};
        doneRecs.forEach(function (a) { days[a.workDate] = 1; });
        var summary = { daysWorked: Object.keys(days).length, totalHours: sum('actualHours'), overtimeHours: sum('overtimeHours'), normalHours: sum('normalHours'), records: recs };
        if (ym) summary.month = ym;
        return respond(200, { success: true, data: summary });
      }
      var am = /^\/attendance\/(history|today)\/([^/]+)$/.exec(sp.path);
      if (am) {
        var wid = decodeURIComponent(am[2]), ast = attendanceStore(wid);
        if (am[1] === 'history') return respond(200, ast.entry.b);
        var td = localStamp(new Date()).slice(0, 10);
        return respond(200, { success: true, data: ast.list.filter(function (a) { return a.workDate === td; }) });
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
      // 公開查驗用工證明（移植 CertificateService.verify）：以證明資料內的查驗碼比對
      var cm = /^\/public\/certificates\/([^/]+)$/.exec(sp.path);
      if (cm) {
        var certNo = decodeURIComponent(cm[1]).trim().toUpperCase();
        var vcode = decodeURIComponent((/[?&]code=([^&]*)/.exec(sp.query) || [])[1] || '').trim().toUpperCase();
        var vr = { certificateNo: certNo };
        if (certNo.indexOf('LEC-') !== 0 || !vcode) {
          vr.valid = false; vr.reason = '證明編號或查驗碼格式不正確';
          return respond(200, { success: true, data: vr });
        }
        var ce = get('GET /certificates/orders/' + encodeURIComponent(certNo.slice(4)));
        var cert = ce && ce.b && ce.b.data;
        if (!cert) {
          vr.valid = false; vr.reason = '查無此證明';
          return respond(200, { success: true, data: vr });
        }
        vr.valid = !!cert.verifyCode && cert.verifyCode === vcode;
        if (!vr.valid) {
          vr.reason = cert.verifyCode ? '查驗碼不符（證明可能遭竄改，或內容已更新，請向農場索取最新版本）' : '此訂單目前不符合開立條件';
          return respond(200, { success: true, data: vr });
        }
        Object.assign(vr, {
          issuer: cert.issuer, farmerName: cert.order.farmerName, cropName: cert.order.cropName,
          categoryName: cert.order.categoryName, periodStart: cert.periodStart, periodEnd: cert.periodEnd,
          workerCount: cert.workerCount, totalHours: cert.totalHours
        });
        return respond(200, { success: true, data: vr });
      }
      var ws = /^\/workers\/([^/]+)\/schedule$/.exec(sp.path);
      if (ws) return workerSchedule(decodeURIComponent(ws[1]), sp.query);
      var tmb = /^\/workers\/([^/]+)\/team-members$/.exec(sp.path);
      if (tmb) return teamRoster(decodeURIComponent(tmb[1]));
      var mm = /^\/matching\/orders\/([^/]+)(\/crew)?$/.exec(sp.path);
      if (mm && !get('GET ' + p) && !(mm[2] ? null : get('GET ' + sp.path))) {
        var tq = (/[?&]teamId=([^&]+)/.exec(sp.query) || [])[1];
        var syn = mm[2] ? (tq && synthCrew(decodeURIComponent(mm[1]), decodeURIComponent(tq))) : synthMatching(decodeURIComponent(mm[1]));
        if (syn) return respond(syn.s, syn.b);
      }
      // 天候戰情：純前端示範沒有後端代理中央氣象署，比照後端未設授權碼回 503，前端改用 Open-Meteo
      if (sp.path === '/weather/towns') return respond(503, { success: false, error: '示範模式未連接中央氣象署（CWA_API_KEY）' });
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
