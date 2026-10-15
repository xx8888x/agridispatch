/**
 * 天候戰情：全台 368 鄉鎮市區即時氣象 + 農務作業建議。
 *
 * 資料來源（依序）：
 *   1. 後端 GET /api/weather/towns → 中央氣象署鄉鎮天氣預報（授權碼 CWA_API_KEY 只在後端）
 *   2. 後端沒設定授權碼或離線、且非 production 模式（demo／純前端 Mock）→ Open-Meteo（免金鑰、瀏覽器直呼，一次批次查 368 個座標）
 *      Open-Meteo 免費 API 限非商業用途，production 模式一律不用，後端失敗就顯示錯誤。
 *   3. Open-Meteo 也失敗（被限流 429／503、斷網、逾時）、且非 production → 純前端 Mock 的錄製資料（GET /weather/towns?sample=1），
 *      標示為「示範用錄製資料」，路演時天候戰情不會空白或一直轉圈。production 絕不使用示範資料。
 *   - 每一個網路請求都有逾時（後端 20 秒、Open-Meteo 10 秒），逾時視為失敗，改走下一個來源。
 *   - 座標表：js/taiwan-towns.js（window.AGRI_TW_TOWNS）；縣市鄉鎮名稱一律用「台」
 *   - 快取：記憶體 + localStorage 15 分鐘（後端另有 30 分鐘快取）
 *
 * 作業建議門檻（啟發式，非官方判定）：
 *   避險：風力 ≥ 17.2 m/s（蒲福 8 級）、日雨量 ≥ 80 mm（氣象署大雨標準）、15 分鐘雨量 ≥ 10 mm、雷雨、體感 ≥ 38°C、氣溫 ≤ 6°C
 *   注意：降雨機率 ≥ 60%、風力 ≥ 13.9 m/s（7 級）、體感 ≥ 35°C、氣溫 ≤ 10°C、正在下雨（紫外線只顯示不列入）
 *   風力取陣風與平均風速（含今日預報最大）中較大者；氣象署陣風來自自動觀測站，只有少數鄉鎮有，沒有時只看風速。
 *   雨量：氣象署為雨量站觀測（近 24 小時 ≥ 80 mm、近 1 小時 ≥ 40 mm 建議避險，近 1 小時 ≥ 1 mm 為降雨中）；
 *   Open-Meteo 為今日預報雨量與 15 分鐘雨量。缺資料時不判讀。
 *   紫外線：氣象署取自一週預報（白天時段），當天 18 時後沒有今日值，只剩明日。
 */
(function (global) {
  'use strict';

  var CACHE_KEY = 'agri_wx_towns_v4';
  var TTL_MS = 15 * 60 * 1000;
  var API = 'https://api.open-meteo.com/v1/forecast';
  var CURRENT = 'temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_gusts_10m';
  var DAILY = 'weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,uv_index_max,wind_gusts_10m_max';

  var BACKEND_TIMEOUT_MS = 20000;
  var OPEN_METEO_TIMEOUT_MS = 10000;
  var SAMPLE_TTL_MS = 2 * 60 * 1000;   // 示範錄製資料只快取 2 分鐘，之後重載會再試即時來源

  var memo = null;

  /**
   * 為整段請求（含讀取回應內容）加逾時：run(signal) 回傳 Promise；超時時中止請求並以「<label>逾時」拒絕。
   * 用 Promise.race，即使 fetch 不理會 abort 也一定會在時限內結束。
   */
  function withTimeout(ms, label, run) {
    var ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = null, timedOut = false;
    var guard = new Promise(function (resolve, reject) {
      timer = setTimeout(function () {
        timedOut = true;
        if (ctl) { try { ctl.abort(); } catch (e) { /* ignore */ } }
        reject(new Error(label + '逾時（超過 ' + Math.round(ms / 1000) + ' 秒沒有回應）'));
      }, ms);
    });
    var work = Promise.resolve().then(function () { return run(ctl ? ctl.signal : undefined); });
    return Promise.race([work, guard]).then(
      function (v) { clearTimeout(timer); return v; },
      function (e) { clearTimeout(timer); throw timedOut ? new Error(label + '逾時（超過 ' + Math.round(ms / 1000) + ' 秒沒有回應）') : e; }
    );
  }

  // 扁平化座標表：[{ county, town, lat, lon, key }]
  function towns() {
    var src = global.AGRI_TW_TOWNS || {};
    var list = [];
    Object.keys(src).forEach(function (county) {
      src[county].forEach(function (t) {
        var town = t[0].replace(/臺/g, '台');
        list.push({ county: county, town: town, lat: t[1], lon: t[2], key: county + town });
      });
    });
    return list;
  }

  function counties() { return Object.keys(global.AGRI_TW_TOWNS || {}); }

  // WMO 天氣代碼 → 中文
  function wmoText(code) {
    var m = {
      0: '晴', 1: '晴時多雲', 2: '多雲', 3: '陰', 45: '霧', 48: '霧凇',
      51: '毛毛雨', 53: '毛毛雨', 55: '濃毛毛雨', 56: '凍毛毛雨', 57: '凍毛毛雨',
      61: '小雨', 63: '中雨', 65: '大雨', 66: '凍雨', 67: '凍雨',
      71: '小雪', 73: '中雪', 75: '大雪', 77: '霰',
      80: '短暫陣雨', 81: '陣雨', 82: '強陣雨', 85: '陣雪', 86: '強陣雪',
      95: '雷雨', 96: '雷雨伴冰雹', 99: '強雷雨伴冰雹'
    };
    return m[code] != null ? m[code] : '—';
  }

  // 紫外線等級（氣象署分級：0–2 低量、3–5 中量、6–7 高量、8–10 過量、11 以上危險）
  function uvLevel(i) {
    if (i == null || isNaN(i)) return null;
    return i >= 11 ? '危險級' : i >= 8 ? '過量級' : i >= 6 ? '高量級' : i >= 3 ? '中量級' : '低量級';
  }

  // 風力峰值（m/s）：陣風與平均風速（含今日預報最大）取大
  function windPeak(w) {
    return Math.max(w.gust || 0, w.gustMax || 0, w.wind || 0, w.windMax || 0);
  }

  function round(v, d) {
    if (v == null || isNaN(v)) return null;
    var p = Math.pow(10, d || 0);
    return Math.round(v * p) / p;
  }

  // 依即時值與今日預報給作業建議：level = stop（建議避險）/ caution（注意）/ ok（適合作業）
  function assess(w) {
    var stop = [], caution = [];
    var gustOnly = Math.max(w.gust || 0, w.gustMax || 0);
    var gust = windPeak(w);
    var gustName = gustOnly > 0 && gustOnly >= gust ? '陣風' : '風力';
    if (gust >= 17.2) stop.push(gustName + ' 8 級以上');
    else if (gust >= 13.9) caution.push(gustName + ' 7 級以上');
    if (w.rainSum >= 80) stop.push((w.rain1h !== undefined ? '24 小時雨量 ' : '日雨量 ') + Math.round(w.rainSum) + ' mm');
    if (w.rain1h >= 40) stop.push('時雨量 ' + Math.round(w.rain1h) + ' mm');
    else if (w.rain1h >= 1) caution.push('降雨中（近 1 小時 ' + w.rain1h + ' mm）');
    if (w.precip >= 10) stop.push('強降雨中');
    else if (w.precip > 0.2) caution.push('降雨中');
    if (w.code >= 95 || w.thunder) stop.push('雷雨');
    if (w.feels >= 38) stop.push('體感 ' + Math.round(w.feels) + '°C 高溫');
    else if (w.feels >= 35) caution.push('體感 ' + Math.round(w.feels) + '°C 注意中暑');
    if (w.temp != null && w.temp <= 6) stop.push('低溫 ' + Math.round(w.temp) + '°C');
    else if (w.temp != null && w.temp <= 10) caution.push('低溫 ' + Math.round(w.temp) + '°C');
    if (w.rainProb >= 60) caution.push('降雨機率 ' + w.rainProb + '%');
    if (stop.length) return { level: 'stop', label: '建議避險', reasons: stop.concat(caution) };
    if (caution.length) return { level: 'caution', label: '注意', reasons: caution };
    return { level: 'ok', label: '適合作業', reasons: [] };
  }

  function normalize(t, r) {
    var c = r.current || {}, d = r.daily || {};
    var at = function (arr, i) { return arr && arr.length > i ? arr[i] : null; };
    var w = {
      county: t.county, town: t.town, key: t.key, lat: t.lat, lon: t.lon,
      time: c.time || null,
      temp: round(c.temperature_2m, 1),
      feels: round(c.apparent_temperature, 1),
      humidity: c.relative_humidity_2m,
      precip: round(c.precipitation, 1) || 0,
      code: c.weather_code,
      wind: round(c.wind_speed_10m, 1),
      gust: round(c.wind_gusts_10m, 1),
      tMax: round(at(d.temperature_2m_max, 0), 0),
      tMin: round(at(d.temperature_2m_min, 0), 0),
      rainProb: at(d.precipitation_probability_max, 0),
      rainSum: round(at(d.precipitation_sum, 0), 1) || 0,
      uv: round(at(d.uv_index_max, 0), 0),
      gustMax: round(at(d.wind_gusts_10m_max, 0), 1),
      tomorrow: {
        code: at(d.weather_code, 1),
        tMax: round(at(d.temperature_2m_max, 1), 0),
        tMin: round(at(d.temperature_2m_min, 1), 0),
        rainProb: at(d.precipitation_probability_max, 1),
        uv: round(at(d.uv_index_max, 1), 0)
      }
    };
    w.text = wmoText(w.code);
    w.tomorrow.text = wmoText(w.tomorrow.code);
    w.uvLevel = uvLevel(w.uv);
    w.tomorrow.uvLevel = uvLevel(w.tomorrow.uv);
    w.assess = assess(w);
    return w;
  }

  function readCache() {
    try {
      var raw = global.localStorage.getItem(CACHE_KEY);
      if (!raw) return null;
      var c = JSON.parse(raw);
      return (c && c.at && Date.now() - c.at < (c.ttl || TTL_MS) && c.list && c.list.length) ? c : null;
    } catch (e) { return null; }
  }

  function writeCache(c) {
    try { global.localStorage.setItem(CACHE_KEY, JSON.stringify(c)); } catch (e) { /* 無痕模式等情況略過 */ }
  }

  // 後端（中央氣象署）回傳的鄉鎮補上 key 與作業建議
  function fromBackend(data) {
    var list = (data.list || []).map(function (w) {
      w.county = String(w.county || '').replace(/臺/g, '台');
      w.town = String(w.town || '').replace(/臺/g, '台');
      w.key = w.county + w.town;
      ['temp', 'feels'].forEach(function (k) { w[k] = round(w[k], 1); });
      ['tMax', 'tMin'].forEach(function (k) { w[k] = round(w[k], 0); });
      w.tomorrow = w.tomorrow || {};
      if (!w.uvLevel) w.uvLevel = uvLevel(w.uv);
      if (!w.tomorrow.uvLevel) w.tomorrow.uvLevel = uvLevel(w.tomorrow.uv);
      w.assess = assess(w);
      return w;
    });
    // 依座標表的縣市鄉鎮順序排列，表格與 Open-Meteo 版一致
    var order = {};
    towns().forEach(function (t, i) { order[t.key] = i; });
    list.sort(function (a, b) { return (order[a.key] != null ? order[a.key] : 9999) - (order[b.key] != null ? order[b.key] : 9999); });
    return { at: Date.now(), provider: 'cwa', source: data.source || '中央氣象署', stale: !!data.stale, list: list };
  }

  function isProduction() { return !!(global.AGRI_CONFIG && global.AGRI_CONFIG.mode === 'production'); }

  /**
   * 取得全台鄉鎮氣象；回傳 { at, provider: 'cwa' | 'open-meteo', source, stale, list: [...] }。
   * apiBase：後端 API 根（例 http://localhost:3000/api）；force=true 略過快取。
   */
  function fetchAll(force, apiBase) {
    if (!force) {
      if (memo && Date.now() - memo.at < (memo.ttl || TTL_MS)) return Promise.resolve(memo);
      var cached = readCache();
      if (cached) {
        cached.list.forEach(function (w) { w.assess = assess(w); }); // 門檻調整後舊快取也照新規則判讀
        memo = cached;
        return Promise.resolve(cached);
      }
    }
    var backendError = null;
    var backend = apiBase
      ? withTimeout(BACKEND_TIMEOUT_MS, '後端氣象服務', function (signal) {
          return fetch(apiBase + '/weather/towns' + (force ? '?force=true' : ''), { signal: signal }).then(function (res) {
            return res.json().catch(function () { return {}; }).then(function (json) {
              if (res.ok && json && json.success && json.data && json.data.list && json.data.list.length) return fromBackend(json.data);
              backendError = (json && (json.error || json.message)) || ('HTTP ' + res.status);
              return null;
            });
          });
        }).catch(function (e) { backendError = e && /逾時/.test(e.message) ? e.message : '無法連線後端'; return null; })
      : Promise.resolve(null);
    return backend.then(function (r) {
      if (r) { memo = r; writeCache(r); return r; }
      if (isProduction()) throw new Error('中央氣象署資料取得失敗（' + (backendError || '未設定 API') + '）');
      // demo／Mock：Open-Meteo 失敗（限流、斷網、逾時）就改用錄製的示範資料，不讓畫面卡住；示範資料也沒有才顯示原本的錯誤
      return fetchOpenMeteo().catch(function (err) {
        return fetchSample(apiBase).then(function (sample) {
          if (!sample) throw err;
          return sample;
        });
      });
    });
  }

  // 示範用錄製資料：純前端 Mock 的 GET /weather/towns?sample=1。永不用於 production；取不到回 null（不丟錯）
  function fetchSample(apiBase) {
    if (!apiBase || isProduction()) return Promise.resolve(null);
    return withTimeout(BACKEND_TIMEOUT_MS, '示範氣象資料', function (signal) {
      return fetch(apiBase + '/weather/towns?sample=1', { signal: signal }).then(function (res) {
        return res.json().catch(function () { return {}; }).then(function (json) {
          if (!(res.ok && json && json.success && json.data && json.data.list && json.data.list.length)) return null;
          var r = fromBackend(json.data);
          r.stale = true;
          r.sample = true;
          r.source = '示範用錄製資料（非即時）';
          r.ttl = SAMPLE_TTL_MS;
          memo = r; writeCache(r);
          return r;
        });
      });
    }).catch(function () { return null; });
  }

  function fetchOpenMeteo() {
    var list = towns();
    if (!list.length) return Promise.reject(new Error('缺少鄉鎮座標表 taiwan-towns.js'));
    var url = API +
      '?latitude=' + list.map(function (t) { return t.lat; }).join(',') +
      '&longitude=' + list.map(function (t) { return t.lon; }).join(',') +
      '&current=' + CURRENT + '&daily=' + DAILY +
      '&forecast_days=2&timezone=Asia%2FTaipei&wind_speed_unit=ms';
    return withTimeout(OPEN_METEO_TIMEOUT_MS, 'Open-Meteo 氣象服務', function (signal) {
      return fetch(url, { signal: signal }).then(function (res) {
        if (!res.ok) throw new Error('氣象 API 回應 HTTP ' + res.status);
        return res.json();
      });
    }).then(function (json) {
      var arr = Array.isArray(json) ? json : [json];
      if (arr.length !== list.length) throw new Error('氣象 API 回傳筆數不符（' + arr.length + '/' + list.length + '）');
      memo = { at: Date.now(), provider: 'open-meteo', source: 'Open-Meteo（示範用）', stale: false, list: list.map(function (t, i) { return normalize(t, arr[i]); }) };
      writeCache(memo);
      return memo;
    });
  }

  // 去掉行政區尾字（鄉鎮市區）取得簡稱，例：枋山鄉 → 枋山
  function shortName(town) { return town.length > 2 ? town.replace(/[鄉鎮市區]$/, '') : town; }

  /**
   * 從自由文字地址比對鄉鎮：先比「縣市+鄉鎮全名」，再比鄉鎮全名，最後比簡稱（須唯一）。
   * 例：「宜蘭縣三星鄉大隱村…」→ 宜蘭縣三星鄉；「枋山愛文芒果合作園區」→ 屏東縣枋山鄉。
   */
  function matchLocation(text) {
    if (!text) return null;
    var s = String(text).replace(/臺/g, '台');
    var list = towns();
    var hit = list.filter(function (t) { return s.indexOf(t.county) >= 0 && s.indexOf(t.town) >= 0; });
    if (!hit.length) {
      // 「苗栗卓蘭…」這類省略縣/市字的寫法：開頭兩字視為縣市，並從字串移除，避免「苗栗」誤配苗栗市
      //（新竹、嘉義同時有縣與市，兩者都納入候選）
      var cs = counties().filter(function (c) { return s.indexOf(c) >= 0; });
      if (!cs.length) cs = counties().filter(function (c) { return s.indexOf(c.slice(0, 2)) === 0; });
      var rest = cs.length ? s.replace(cs[0], '').replace(new RegExp('^' + cs[0].slice(0, 2)), '') : s;
      var pool = cs.length ? list.filter(function (t) { return cs.indexOf(t.county) >= 0; }) : list;
      hit = pool.filter(function (t) { return rest.indexOf(t.town) >= 0; });
      if (!hit.length) {
        var byShort = pool.filter(function (t) { return shortName(t.town).length >= 2 && rest.indexOf(shortName(t.town)) >= 0; });
        if (byShort.length === 1) hit = byShort;
      }
    }
    if (hit.length > 1) hit.sort(function (a, b) { return b.town.length - a.town.length; });
    return hit[0] || null;
  }

  global.AgriWeather = {
    fetchAll: fetchAll,
    counties: counties,
    towns: towns,
    wmoText: wmoText,
    windPeak: windPeak,
    assess: assess,
    matchLocation: matchLocation,
    TTL_MS: TTL_MS
  };
})(window);
