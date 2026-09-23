// 智農派工 AgriDispatch - Core Simulation Logic (北部產區樞紐 ╳ 三層混合模型 ╳ 專車物流精算)

// 1. Multilingual Dictionary for Worker Portal
const i18n = {
  zh: {
    title: "今日派工任務卡",
    badge: "已派工・專車接送",
    shiftTime: "出勤時間",
    timeVal: "06:30 集合上車 / 07:00 ~ 16:00 作業",
    van: "接送專車",
    vanVal: "智農 1 號專車 (車號: RBF-8899 / 司機: 張大哥)",
    location: "作業地點",
    locFarm: "桃園市八德區興豐路 有機蔬菜專銷班 溫室第 3 區",
    locIndoor: "八德蔬果預冷冷鏈包裝中心 (室內B棟)",
    task: "主要作業內容",
    taskFarm: "有機葉菜採收、精緻挑菜、田間分級修葉、生鮮小包裝",
    taskIndoor: "蔬果預冷降溫、自動光波秤重分級、外銷蔬果裝箱貼標",
    safety: "農務安全注意事項",
    safetyVal: "高溫防暑！每 45 分鐘強制至遮陽棚飲水 300cc，務必穿戴防穿刺防滑鞋。",
    btnClockIn: "📍 GPS 抵達現場數位打卡",
    clockedInMsg: "✅ 打卡成功！GPS 定位: 桃園市八德區田區 (時間: 06:58)",
    salaryTitle: "本月累計所得與政府就業獎勵",
    basePay: "外展保障基本底薪",
    bonusAgri: "農業部外展就業獎勵津貼 (第 3 個月)",
    bonusVal: "+ NT$ 8,800 (中央撥付已入帳)",
    transportSub: "專車交通與高溫補助津貼",
    totalEst: "本月實領預估"
  },
  vi: {
    title: "Thẻ Nhiệm Vụ Hôm Nay",
    badge: "Đã Điều Phái・Xe Đón Tận Nơi",
    shiftTime: "Thời Gian Làm Việc",
    timeVal: "06:30 Tập hợp lên xe / 07:00 ~ 16:00 Làm việc",
    van: "Xe Đưa Đón",
    vanVal: "Xe Chuyên Dụng Số 1 (Biển số: RBF-8899 / Tài xế: Anh Trương)",
    location: "Địa Điểm Làm Việc",
    locFarm: "Khu Nhà Kính Rau Hữu Cơ Số 3 - Huyện Bát Đức, Đào Viên",
    locIndoor: "Trung Tâm Đóng Gói Chuỗi Lạnh Bát Đức (Khu B - Dây chuyền)",
    task: "Nội Dung Công Việc",
    taskFarm: "Thu hoạch rau hữu cơ, nhặt lá úa, đóng gói rau tươi loại nhỏ",
    taskIndoor: "Làm mát rau quả, phân loại cân nặng tự động, đóng thùng xuất khẩu",
    safety: "Quy Tắc An Toàn Lao Động",
    safetyVal: "Chú ý chống sốc nhiệt! Mỗi 45 phút uống 300cc nước tại lán râm, mang giày bảo hộ.",
    btnClockIn: "📍 Điểm danh GPS tại hiện trường",
    clockedInMsg: "✅ Điểm danh thành công! GPS: Bát Đức, Đào Viên (06:58)",
    salaryTitle: "Thu Nhập Tháng & Trợ Cấp Chính Phủ",
    basePay: "Lương Cơ Bản Đảm Bảo",
    bonusAgri: "Tiền Thưởng Việc Làm Nông Nghiệp Bộ NN (Tháng 3)",
    bonusVal: "+ NT$ 8,800 (Đã Chuyển Khoản)",
    transportSub: "Phụ Cấp Xe Đi Lại & Nhiệt Độ",
    totalEst: "Ước Tính Thực Lĩnh Tháng Này"
  },
  id: {
    title: "Kartu Tugas Hari Ini",
    badge: "Ditugaskan・Penjemputan Armada",
    shiftTime: "Jam Kerja",
    timeVal: "06:30 Kumpul naik mobil / 07:00 ~ 16:00 Kerja",
    van: "Mobil Jemputan",
    vanVal: "Armada No. 1 (Plat: RBF-8899 / Sopir: Mas Chang)",
    location: "Lokasi Kerja",
    locFarm: "Greenhouse Sayur Organik Blok 3 - Bade, Taoyuan",
    locIndoor: "Gudang Pendingin Cold Chain Bade (Gedung B Sortir)",
    task: "Tugas Utama",
    taskFarm: "Panen sayur organik, pembersihan daun, kemas sayur segar",
    taskIndoor: "Precooling sayur, sortir berat otomatis, packing ekspor",
    safety: "Keselamatan Kerja Pertanian",
    safetyVal: "Waspada dehidrasi panas! Istirahat minum 300cc tiap 45 menit, pakai sepatu safety.",
    btnClockIn: "📍 Presensi Digital GPS di Lapangan",
    clockedInMsg: "✅ Presensi Berhasil! Lokasi GPS: Bade Taoyuan (06:58)",
    salaryTitle: "Akumulasi Gaji & Subsidi Pemerintah",
    basePay: "Gaji Pokok Terjamin",
    bonusAgri: "Insentif Kerja Pertanian Dept. Pertanian (Bulan ke-3)",
    bonusVal: "+ NT$ 8,800 (Sudah Masuk Rekening)",
    transportSub: "Tunjangan Armada & Transport",
    totalEst: "Estimasi Bersih Bulan Ini"
  }
};

let currentLang = 'zh';
let isRainMode = false;

// 2. 作物基準資料庫（以北部產區為核心樞紐，支援中南部標竿作物）
const cropSpecs = {
  taoyuan_veg: { name: "桃園溫室有機蔬菜 (八德/大溪)", baseHoursPerFen: 20, workersPerFen: 3, rate: 290, region: "core_35km", task: "採收 / 精緻挑菜 / 生鮮小包裝" },
  scallion: { name: "宜蘭三星青蔥 (三星/壯圍)", baseHoursPerFen: 24, workersPerFen: 3, rate: 300, region: "cross_region", task: "拔蔥 / 專用洗蔥機作業 / 分級束捆" },
  hsinchu_tea: { name: "新竹手採茶/坪林包種茶", baseHoursPerFen: 32, workersPerFen: 4, rate: 320, region: "cross_region", task: "一心二葉精緻手採 / 採摘保鮮" },
  xinpu_citrus: { name: "新竹新埔柑橘/柿餅", baseHoursPerFen: 26, workersPerFen: 3, rate: 300, region: "cross_region", task: "產季採收 / 削皮整枝 / 裝箱" },
  mango: { name: "愛文芒果 (屏東枋山/台南玉井)", baseHoursPerFen: 28, workersPerFen: 4, rate: 300, region: "cross_region", task: "外銷級留蒂採收 / 疏果防菌套袋" },
  onion: { name: "洋蔥 (屏東恆春/高樹)", baseHoursPerFen: 32, workersPerFen: 4, rate: 290, region: "cross_region", task: "拔蔥 / 剪根剪葉 / 田間裝袋 20kg" },
  pepper: { name: "設施彩椒/小黃瓜 (彰化/雲林)", baseHoursPerFen: 20, workersPerFen: 3, rate: 310, region: "cross_region", task: "整枝修芽 / 溫室精準分批採摘" }
};

// Initialize app when DOM ready
document.addEventListener('DOMContentLoaded', () => {
  initCalculator();
  updateWorkerLang('zh');
  setupEventListeners();
});

// Calculator Logic (三層混合模型 ╳ 專車物流精算)
function initCalculator() {
  const cropSelect = document.getElementById('calc-crop');
  const areaInput = document.getElementById('calc-area');
  const planRadios = document.getElementsByName('calc-plan');
  const zoneRadios = document.getElementsByName('calc-zone');

  function calculate() {
    const cropKey = cropSelect ? cropSelect.value : 'taoyuan_veg';
    const area = parseFloat(areaInput ? areaInput.value : 5) || 1;
    let plan = 'early_bird';
    for (const r of planRadios) {
      if (r.checked) plan = r.value;
    }
    let zone = 'core_35km';
    for (const z of zoneRadios) {
      if (z.checked) zone = z.value;
    }

    const crop = cropSpecs[cropKey] || cropSpecs.taoyuan_veg;
    const totalHours = Math.round(crop.baseHoursPerFen * area);
    const suggestedWorkers = Math.max(2, Math.round(crop.workersPerFen * (area > 3 ? 1.5 : 1)));
    const estDays = (totalHours / (suggestedWorkers * 8)).toFixed(1);

    let unitRate = crop.rate;
    let discount = 1.0;
    let planDesc = "【Tier 1】核心外展移工主力早鳥包 (9折・享保證出工特權)";
    let surcharge = zone === 'cross_region' ? 1200 : 0;

    if (plan === 'early_bird') {
      discount = 0.90; // 9折
      planDesc = "【Tier 1】核心外展移工早鳥包 (預售 9 折・20%定金鎖定)";
    } else if (plan === 'hourly_flex') {
      discount = 1.0;
      unitRate = 310;
      planDesc = "【Tier 2】在地彈性兼職包 (隨叫隨派・時薪 $310・零底薪負擔)";
    } else if (plan === 'turnkey') {
      discount = 0.95;
      planDesc = "【Tier 3】成果統包專業代耕 (無人機/機耕驗收付款・免監工)";
    }

    const standardTotal = totalHours * unitRate;
    const discountedTotal = Math.round(standardTotal * discount);
    const actualTotal = discountedTotal + surcharge;
    const deposit = plan === 'early_bird' ? Math.round(discountedTotal * 0.2) : (plan === 'turnkey' ? Math.round(discountedTotal * 0.3) : 0);
    const savings = standardTotal - discountedTotal;

    setText('calc-out-hours', `${totalHours} 工時`);
    setText('calc-out-workers', `${suggestedWorkers} 位專業農工 (約 ${estDays} 天完工)`);
    setText('calc-out-plan-desc', planDesc);
    setText('calc-out-standard', `NT$ ${(standardTotal + surcharge).toLocaleString()}`);
    setText('calc-out-final', `NT$ ${actualTotal.toLocaleString()}`);
    setText('calc-out-deposit', deposit > 0 ? `NT$ ${deposit.toLocaleString()} (${plan === 'early_bird' ? '20% 鎖約定金' : '30% 備料款'})` : '免預付 (完工結算)');
    setText('calc-out-savings', savings > 0 ? `已省下 NT$ ${savings.toLocaleString()} (${Math.round((1 - discount) * 100)}% 優惠)` : (surcharge > 0 ? `跨區專車 +$1,200` : '一般標準費率'));

    const zoneBadge = document.getElementById('calc-out-zone-badge');
    if (zoneBadge) {
      zoneBadge.innerText = zone === 'cross_region' ? '跨區專線 (+NT$ 1,200)' : '北部核心區 (免跨區費)';
      zoneBadge.className = zone === 'cross_region' ? 'text-xs font-bold text-amber-400' : 'text-xs font-bold text-emerald-400';
    }
  }

  if (cropSelect) cropSelect.addEventListener('change', calculate);
  if (areaInput) areaInput.addEventListener('input', calculate);
  for (const r of planRadios) r.addEventListener('change', calculate);
  for (const z of zoneRadios) z.addEventListener('change', calculate);

  calculate();
}

// Weather Emergency Simulation Trigger
window.toggleWeatherMode = function() {
  isRainMode = !isRainMode;
  const banner = document.getElementById('weather-status-banner');
  const alertBox = document.getElementById('rain-alert-box');
  const btn = document.getElementById('weather-toggle-btn');
  const dispatchCards = document.querySelectorAll('.worker-status-card');

  if (isRainMode) {
    if (banner) {
      banner.className = "p-4 rounded-xl bg-blue-900/90 text-white border border-blue-400/50 shadow-lg shadow-blue-900/30 transition-all-300";
      banner.innerHTML = `
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="text-3xl animate-bounce">🌧️</span>
            <div>
              <div class="flex items-center gap-2">
                <span class="px-2 py-0.5 text-xs font-bold bg-red-500 text-white rounded-full">北部氣象站 豪雨特報啟動</span>
                <span class="text-xs text-blue-200">即時降雨量 > 45mm/hr</span>
              </div>
              <p class="font-bold text-lg mt-0.5">田區安全停工 ➔ 自動啟動「八德蔬果預冷冷鏈包裝中心」緊急避險</p>
            </div>
          </div>
          <div class="text-right">
            <span class="text-xs text-blue-200 block">避險效益</span>
            <span class="text-emerald-400 font-black text-xl">+ NT$ 62,500 營收保全</span>
          </div>
        </div>
      `;
    }

    if (alertBox) alertBox.classList.remove('hidden');
    if (btn) {
      btn.innerHTML = `☀️ 模擬天候轉晴（返回八德溫室作業）`;
      btn.className = "px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-sm font-bold shadow-md transition";
    }

    dispatchCards.forEach((card) => {
      const locElem = card.querySelector('.loc-text');
      const taskElem = card.querySelector('.task-text');
      const badgeElem = card.querySelector('.status-badge');
      if (locElem) locElem.innerHTML = `<span class="text-blue-600 font-bold">🏢 八德蔬果預冷冷鏈包裝中心 (室內B棟)</span>`;
      if (taskElem) taskElem.innerHTML = `蔬果預冷降溫、自動光波秤重、外銷包裝貼標`;
      if (badgeElem) {
        badgeElem.className = "status-badge px-2.5 py-1 rounded-full text-xs font-bold bg-blue-100 text-blue-800 border border-blue-200";
        badgeElem.innerText = "冷鏈避險中 (風雨無阻)";
      }
    });

    showToast("⚠️ 氣象警報！已自動將 20 名外展農工專車改派至室內八德冷鏈集貨場，零閒置虧損！");
  } else {
    if (banner) {
      banner.className = "p-4 rounded-xl bg-emerald-900/90 text-white border border-emerald-400/50 shadow-lg shadow-emerald-900/30 transition-all-300";
      banner.innerHTML = `
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="text-3xl">☀️</span>
            <div>
              <div class="flex items-center gap-2">
                <span class="px-2 py-0.5 text-xs font-bold bg-emerald-500 text-white rounded-full">天候優良 適合農作</span>
                <span class="text-xs text-emerald-200">桃園八德 晴朗 28°C</span>
              </div>
              <p class="font-bold text-lg mt-0.5">全區田間出勤中・外展農工出勤率 100% (20/20 人在崗)</p>
            </div>
          </div>
          <div class="text-right">
            <span class="text-xs text-emerald-200 block">今日調度總工時</span>
            <span class="text-amber-400 font-black text-xl">160 工時 (已滿檔)</span>
          </div>
        </div>
      `;
    }

    if (alertBox) alertBox.classList.add('hidden');
    if (btn) {
      btn.innerHTML = `🌧️ 模擬突發暴雨（啟動冷鏈避險機制）`;
      btn.className = "px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-500 text-white text-sm font-bold shadow-md transition";
    }

    dispatchCards.forEach((card) => {
      const locElem = card.querySelector('.loc-text');
      const taskElem = card.querySelector('.task-text');
      const badgeElem = card.querySelector('.status-badge');
      if (locElem) locElem.innerHTML = `<span class="text-emerald-700 font-bold">🌾 八德有機蔬菜專銷班 溫室第 3 區</span>`;
      if (taskElem) taskElem.innerHTML = `有機葉菜採收、修葉分級、生鮮小包裝`;
      if (badgeElem) {
        badgeElem.className = "status-badge px-2.5 py-1 rounded-full text-xs font-bold bg-emerald-100 text-emerald-800 border border-emerald-200";
        badgeElem.innerText = "田間出勤中";
      }
    });

    showToast("☀️ 天候恢復晴朗，已切換為戶外溫室作業模式。");
  }

  updateWorkerLang(currentLang);
};

// Switch Role Views
window.switchRole = function(roleId) {
  const views = ['view-farmer', 'view-dispatch', 'view-worker', 'view-talent'];
  views.forEach(v => {
    const el = document.getElementById(v);
    if (el) {
      if (v === `view-${roleId}`) {
        el.classList.remove('hidden');
      } else {
        el.classList.add('hidden');
      }
    }
  });

  const buttons = document.querySelectorAll('.role-nav-btn');
  buttons.forEach(btn => {
    if (btn.dataset.role === roleId) {
      btn.className = "role-nav-btn px-4 py-2 rounded-lg bg-emerald-600 text-white font-bold text-sm shadow transition flex items-center gap-2";
    } else {
      btn.className = "role-nav-btn px-4 py-2 rounded-lg bg-slate-100 text-slate-600 hover:bg-slate-200 font-medium text-sm transition flex items-center gap-2";
    }
  });
};

// Multilingual switcher for worker portal
window.updateWorkerLang = function(lang) {
  currentLang = lang;
  const dict = i18n[lang] || i18n.zh;

  setText('w-card-title', dict.title);
  setText('w-badge', dict.badge);
  setText('w-shift-time', dict.shiftTime);
  setText('w-time-val', dict.timeVal);
  setText('w-van', dict.van);
  setText('w-van-val', dict.vanVal);
  setText('w-location', dict.location);
  setText('w-loc-val', isRainMode ? dict.locIndoor : dict.locFarm);
  setText('w-task', dict.task);
  setText('w-task-val', isRainMode ? dict.taskIndoor : dict.taskFarm);
  setText('w-safety', dict.safety);
  setText('w-safety-val', dict.safetyVal);
  setText('w-btn-clockin', dict.btnClockIn);
  setText('w-salary-title', dict.salaryTitle);
  setText('w-base-pay', dict.basePay);
  setText('w-bonus-agri', dict.bonusAgri);
  setText('w-bonus-val', dict.bonusVal);
  setText('w-transport-sub', dict.transportSub);
  setText('w-total-est', dict.totalEst);

  ['zh', 'vi', 'id'].forEach(l => {
    const btn = document.getElementById(`btn-lang-${l}`);
    if (btn) {
      if (l === lang) {
        btn.className = "px-3 py-1 rounded-md text-xs font-bold bg-emerald-600 text-white shadow";
      } else {
        btn.className = "px-3 py-1 rounded-md text-xs font-medium bg-slate-200 text-slate-700 hover:bg-slate-300";
      }
    }
  });
};

// Clock-in simulation
window.handleClockIn = function() {
  const dict = i18n[currentLang] || i18n.zh;
  const clockBtn = document.getElementById('w-btn-clockin');
  if (clockBtn) {
    clockBtn.disabled = true;
    clockBtn.className = "w-full py-3 rounded-xl bg-slate-300 text-slate-600 font-bold cursor-not-allowed text-center";
    clockBtn.innerText = dict.clockedInMsg;
  }
  showToast(dict.clockedInMsg);
};

// Farmer Book Dispatch Form Submit
window.handleFarmerBooking = function(e) {
  if (e) e.preventDefault();
  const crop = document.getElementById('calc-crop')?.selectedOptions[0]?.text || "桃園溫室蔬菜";
  const area = document.getElementById('calc-area')?.value || "5";
  showToast(`🎉 預約成功！已為您排入【${crop} / ${area} 分地】早鳥派工排程，調度專員將於 2 小時內與您聯繫！`);
  setTimeout(() => {
    switchRole('dispatch');
  }, 1000);
};

// Talent Onboarding Form Submit
window.handleTalentSubmit = function(e) {
  if (e) e.preventDefault();
  const consent = document.getElementById('talent-consent');
  if (!consent || !consent.checked) {
    alert("⚠️ 請先閱讀並勾選《個人資料蒐集、處理及利用同意書》，保障合規權益！");
    return;
  }
  const name = document.getElementById('talent-name')?.value || "農工朋友";
  showToast(`✅ 感謝 ${name}！資料已合規加密登錄入庫，我們將主動為您推介高薪合法農事派遣！`);
  document.getElementById('talent-form')?.reset();
};

function setText(id, text) {
  const el = document.getElementById(id);
  if (el) el.innerText = text;
}

function showToast(msg) {
  let toast = document.getElementById('app-toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'app-toast';
    toast.className = "fixed bottom-6 right-6 z-50 px-5 py-3 rounded-xl bg-slate-900 text-white text-sm font-medium shadow-2xl border border-slate-700 transition-all transform translate-y-10 opacity-0 duration-300";
    document.body.appendChild(toast);
  }
  toast.innerHTML = msg;
  toast.classList.remove('translate-y-10', 'opacity-0');
  setTimeout(() => {
    toast.classList.add('translate-y-10', 'opacity-0');
  }, 4000);
}

function setupEventListeners() {}
