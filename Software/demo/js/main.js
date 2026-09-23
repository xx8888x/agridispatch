// 智農派工 (AgriDispatch) - 四大使用者版本核心前端邏輯 (北部產區樞紐 ╳ 三層混合模型 ╳ 專車物流精算升級版)

const API_BASE = window.location.origin.startsWith('http')
  ? `${window.location.origin}/api`
  : 'http://localhost:3000/api';

let currentUser = {
  username: 'farmer',
  role: 'farmer',
  name: '陳建龍 (阿龍班長)',
  roleDesc: '農場主 / 桃園八德有機蔬菜產銷班'
};

let currentLang = 'zh';
let isRainMode = false;
let chartInstances = {};

// 移工多語系本地備援字典 (Offline fallback)
const i18nFallback = {
  zh: {
    title: "今日派工任務卡",
    badge: "已派工・專車接送",
    badgeRain: "🌧️ 冷鏈避險・專車轉移",
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
    safetyFarm: "高溫防暑！每 45 分鐘強制至遮陽棚飲水 300cc，務必穿戴防穿刺防滑鞋。",
    safetyIndoor: "冷鏈低溫防護：室內預冷室溫度 12~15°C，請穿著防風保暖外套與防滑工作靴。",
    btnClockIn: "📍 GPS 抵達現場數位打卡",
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
    badgeRain: "🌧️ Phòng Tránh Mưa・Chuyển Kho Lạnh",
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
    safetyFarm: "Chú ý chống sốc nhiệt! Mỗi 45 phút uống 300cc nước tại lán râm, mang giày bảo hộ.",
    safetyIndoor: "Bảo vệ nhiệt độ thấp trong kho lạnh: Nhiệt độ 12~15°C, vui lòng mặc áo khoác mỏng và giày chống trượt.",
    btnClockIn: "📍 Điểm danh GPS tại hiện trường",
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
    badgeRain: "🌧️ Mitigasi Hujan・Pindah Cold Chain",
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
    safetyFarm: "Waspada dehidrasi panas! Istirahat minum 300cc tiap 45 menit, pakai sepatu safety.",
    safetyIndoor: "Perlindungan suhu dingin: Suhu ruangan 12~15°C, gunakan jaket dan sepatu anti slip.",
    btnClockIn: "📍 Presensi Digital GPS di Lapangan",
    salaryTitle: "Akumulasi Gaji & Subsidi Pemerintah",
    basePay: "Gaji Pokok Terjamin",
    bonusAgri: "Insentif Kerja Pertanian Dept. Pertanian (Bulan ke-3)",
    bonusVal: "+ NT$ 8,800 (Sudah Masuk Rekening)",
    transportSub: "Tunjangan Armada & Transport",
    totalEst: "Estimasi Bersih Bulan Ini"
  }
};

// 作物基準資料庫（以北部產區為核心樞紐，支援中南部標竿作物）
const cropSpecs = {
  taoyuan_veg: { name: "桃園溫室有機蔬菜 (八德/大溪)", baseHoursPerFen: 20, workersPerFen: 3, rate: 290, region: "core_35km", task: "採收 / 精緻挑菜 / 生鮮小包裝" },
  scallion: { name: "宜蘭三星青蔥 (三星/壯圍)", baseHoursPerFen: 24, workersPerFen: 3, rate: 300, region: "cross_region", task: "拔蔥 / 專用洗蔥機作業 / 分級束捆" },
  hsinchu_tea: { name: "新竹手採茶/坪林包種茶", baseHoursPerFen: 32, workersPerFen: 4, rate: 320, region: "cross_region", task: "一心二葉精緻手採 / 採摘保鮮" },
  xinpu_citrus: { name: "新竹新埔柑橘/柿餅", baseHoursPerFen: 26, workersPerFen: 3, rate: 300, region: "cross_region", task: "產季採收 / 削皮整枝 / 裝箱" },
  mango: { name: "愛文芒果 (屏東枋山/台南玉井)", baseHoursPerFen: 28, workersPerFen: 4, rate: 300, region: "cross_region", task: "外銷級留蒂採收 / 疏果防菌套袋" },
  onion: { name: "洋蔥 (屏東恆春/高樹)", baseHoursPerFen: 32, workersPerFen: 4, rate: 290, region: "cross_region", task: "拔蔥 / 剪根剪葉 / 田間裝袋 20kg" },
  pepper: { name: "設施彩椒/小黃瓜 (彰化/雲林)", baseHoursPerFen: 20, workersPerFen: 3, rate: 310, region: "cross_region", task: "整枝修芽 / 溫室精準分批採摘" }
};

document.addEventListener('DOMContentLoaded', () => {
  // 檢測原生 Android 系統語言
  if (window.AgriNativeBridge && typeof window.AgriNativeBridge.getSystemLanguage === 'function') {
    currentLang = window.AgriNativeBridge.getSystemLanguage();
  }

  // 處理 URL Hash
  const hash = window.location.hash.replace('#', '');
  if (['farmer', 'worker_foreign', 'worker_local', 'admin_tech', 'showcase'].includes(hash)) {
    switchUserRole(hash);
  } else {
    switchUserRole('farmer');
  }

  initCalculator();
  updateWorkerLang(currentLang);
});

// 1. 切換使用者版本與視圖
function switchUserRole(role) {
  currentUser.role = role;
  const portals = ['farmer', 'worker_foreign', 'worker_local', 'admin_tech', 'showcase'];

  // 切換視圖顯隱
  portals.forEach(p => {
    const el = document.getElementById(`portal-${p}`) || document.getElementById(`portal-${p.replace('_', '-')}`);
    if (el) {
      if (p === role) el.classList.remove('hidden');
      else el.classList.add('hidden');
    }
  });

  // 更新桌面端導覽按鈕
  document.querySelectorAll('.role-portal-btn').forEach(btn => {
    btn.className = 'role-portal-btn px-3 py-1.5 rounded-lg bg-slate-800 text-slate-300 hover:bg-slate-700 font-medium text-xs transition flex items-center gap-1.5';
  });

  const activeBtnMap = {
    farmer: 'btn-portal-farmer',
    worker_foreign: 'btn-portal-foreign',
    worker_local: 'btn-portal-local',
    admin_tech: 'btn-portal-admin',
    showcase: 'btn-portal-showcase'
  };
  const activeBtn = document.getElementById(activeBtnMap[role]);
  if (activeBtn) {
    activeBtn.className = 'role-portal-btn px-3 py-1.5 rounded-lg bg-emerald-600 text-white font-bold text-xs shadow transition flex items-center gap-1.5';
  }

  // 更新手機端導覽
  document.querySelectorAll('.m-role-btn').forEach(btn => {
    btn.className = 'm-role-btn px-2.5 py-1 text-slate-400 font-medium';
  });
  const mActiveMap = {
    farmer: 'm-btn-farmer',
    worker_foreign: 'm-btn-foreign',
    worker_local: 'm-btn-local',
    admin_tech: 'm-btn-admin',
    showcase: 'm-btn-showcase'
  };
  const mActive = document.getElementById(mActiveMap[role]);
  if (mActive) {
    mActive.className = 'm-role-btn px-2.5 py-1 text-emerald-400 font-bold border-b-2 border-emerald-400';
  }

  // 更新右上角使用者資訊
  const roleInfo = {
    farmer: { name: '陳建龍 (阿龍班長)', roleDesc: '農場主 / 桃園八德有機蔬菜產銷班' },
    worker_foreign: { name: 'Nguyễn Văn Hùng (阮文雄)', roleDesc: '特許外展移工 / 越南 (#001)' },
    worker_local: { name: '陳建宏 (資深機耕技師)', roleDesc: '本國農機領航員 / 雙證照飛手' },
    admin_tech: { name: '智農科技調度總監', roleDesc: '科技公司後台 / 系統調度戰情室' },
    showcase: { name: '🌟 全景商業展示 Pitch', roleDesc: '投資人 / 主管機關 / 合作農企業全景體驗' }
  };

  const info = roleInfo[role] || roleInfo.farmer;
  setText('current-user-name', info.name);
  setText('current-user-role', info.roleDesc);
  window.location.hash = role;

  // 若切換至科技後台，初始化動態圖表
  if (role === 'admin_tech') {
    setTimeout(initTechCharts, 100);
  }
}

// 2. 登入 Modal 控制
function openLoginModal() {
  const modal = document.getElementById('login-modal');
  if (modal) modal.classList.remove('hidden');
}

function closeLoginModal() {
  const modal = document.getElementById('login-modal');
  if (modal) modal.classList.add('hidden');
}

async function quickLogin(role) {
  closeLoginModal();
  switchUserRole(role);

  const creds = {
    farmer: { username: 'farmer', password: '123456' },
    worker_foreign: { username: 'worker_foreign', password: '123456' },
    worker_local: { username: 'worker_local', password: '123456' },
    admin_tech: { username: 'admin_tech', password: 'admin888' }
  };

  try {
    const res = await fetch(`${API_BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(creds[role])
    });
    const data = await res.json();
    if (data.success) {
      showNotification(`🔑 登入成功！歡迎【${data.user.name}】進入系統。`);
    }
  } catch (e) {
    showNotification(`🔑 已切換至【${role}】專屬版本（展示模式）`);
  }
}

// 3. 科技公司後台圖表繪製 (Charts)
async function initTechCharts() {
  if (typeof Chart === 'undefined') return;

  try {
    // 圖表 1: 雙軌營收利潤分流圓餅圖 (Year 1 GMV 925.7 萬：科技公司 18% + 交通加成 vs 合作社 82%)
    const ctx1 = document.getElementById('chart-revenue-split')?.getContext('2d');
    if (ctx1) {
      if (chartInstances.chart1) chartInstances.chart1.destroy();
      chartInstances.chart1 = new Chart(ctx1, {
        type: 'doughnut',
        data: {
          labels: ['科技公司技術代管費 18% + 專車加成 (NT$ 2,077,200)', '合作社移工法定成本平移 82% (NT$ 7,179,800)'],
          datasets: [{
            data: [2077200, 7179800],
            backgroundColor: ['#10B981', '#3B82F6'],
            borderWidth: 0
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: { legend: { position: 'bottom', labels: { color: '#94A3B8', boxWidth: 12, font: { size: 11 } } } }
        }
      });
    }

    // 圖表 2: 北部常態溫室 + 產季跨區 GMV 走勢 (萬元)
    const ctx2 = document.getElementById('chart-monthly-gmv')?.getContext('2d');
    if (ctx2) {
      if (chartInstances.chart2) chartInstances.chart2.destroy();
      chartInstances.chart2 = new Chart(ctx2, {
        type: 'bar',
        data: {
          labels: ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月'],
          datasets: [{
            label: 'GMV 總營收 (萬元)',
            data: [52, 55, 68, 85, 120, 145, 130, 95, 72, 60, 58, 62],
            backgroundColor: '#F59E0B',
            borderRadius: 6
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: { legend: { display: false } },
          scales: {
            x: { ticks: { color: '#94A3B8', font: { size: 10 } }, grid: { display: false } },
            y: { ticks: { color: '#94A3B8' }, grid: { color: '#334155' } }
          }
        }
      });
    }

    // 圖表 3: 突發降雨出勤避險成效 (桃園溫室轉派八德冷鏈)
    const ctx3 = document.getElementById('chart-weather-hedge')?.getContext('2d');
    if (ctx3) {
      if (chartInstances.chart3) chartInstances.chart3.destroy();
      chartInstances.chart3 = new Chart(ctx3, {
        type: 'line',
        data: {
          labels: ['週一', '週二', '週三(豪雨特報)', '週四', '週五', '週六', '週日'],
          datasets: [
            {
              label: '啟動冷鏈避險',
              data: [100, 100, 100, 100, 100, 95, 95],
              borderColor: '#10B981',
              backgroundColor: 'rgba(16, 185, 129, 0.1)',
              fill: true,
              tension: 0.3
            },
            {
              label: '傳統無避險',
              data: [100, 100, 0, 100, 100, 95, 95],
              borderColor: '#EF4444',
              borderDash: [5, 5],
              tension: 0.3
            }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: { legend: { position: 'bottom', labels: { color: '#94A3B8', boxWidth: 10 } } },
          scales: {
            x: { ticks: { color: '#94A3B8' }, grid: { display: false } },
            y: { ticks: { color: '#94A3B8' }, grid: { color: '#334155' }, max: 100 }
          }
        }
      });
    }
  } catch (e) {
    console.log('Charts rendered in offline mode', e);
  }
}

// 4. 農場主智慧派工試算器 (三層混合模型 ╳ 專車物流精算)
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
    let planDesc = "【Tier 1】產季早鳥工時包 (9折・享保證出工特權)";
    let surcharge = zone === 'cross_region' ? 1200 : 0;

    if (plan === 'early_bird') {
      discount = 0.90; // 9折
      planDesc = "【Tier 1】核心外展移工早鳥包 (預售 9 折・20%定金鎖定)";
    } else if (plan === 'hourly_flex') {
      discount = 1.0;
      unitRate = 310; // 本土兼職臨時計時費率
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
    setText('calc-out-workers', `${suggestedWorkers} 位 (約 ${estDays} 天)`);
    setText('calc-out-plan-desc', planDesc);
    setText('calc-out-standard', `NT$ ${(standardTotal + surcharge).toLocaleString()}`);
    setText('calc-out-final', `NT$ ${actualTotal.toLocaleString()}`);
    setText('calc-out-deposit', deposit > 0 ? `NT$ ${deposit.toLocaleString()} (${plan === 'early_bird' ? '20% 定金' : '30% 備料金'})` : '免預付 (完工結算)');
    setText('calc-out-savings', savings > 0 ? `已省下 NT$ ${savings.toLocaleString()} (${Math.round((1 - discount) * 100)}% 優惠)` : (surcharge > 0 ? `跨區專車 +$1,200` : '標準費率計價'));
    
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

// 5. 小農預約送出
async function handleFarmerBooking(e) {
  if (e) e.preventDefault();
  const cropText = document.getElementById('calc-crop')?.selectedOptions[0]?.text || "桃園溫室蔬菜";
  const area = document.getElementById('calc-area')?.value || "5";
  const farmerName = prompt("請輸入您的姓名或農場名稱：", "八德有機蔬菜產銷班") || "示範農場";
  const phone = prompt("請輸入聯絡電話 (手機)：", "0912-345-678") || "0912-345-678";

  const payload = {
    farmerName,
    phone,
    farmLocation: "桃園市八德區興豐路",
    cropId: document.getElementById('calc-crop')?.value || "taoyuan_veg",
    area: parseFloat(area),
    plan: "early_bird"
  };

  try {
    const res = await fetch(`${API_BASE}/orders`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const result = await res.json();
    showNotification(`🎉 預約成功！訂單編號: ${result.data?.id || 'ORD-2026-NEW'}，已鎖定【${cropText} / ${area} 分地】早鳥派工配額！`);
  } catch (err) {
    showNotification(`🎉 預約已送出（展示模式）！已為「${farmerName}」鎖定【${cropText}】早鳥配額。`);
  }
}

// 6. 外籍移工多語切換
function updateWorkerLang(lang) {
  currentLang = lang;

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

  const dict = i18nFallback[currentLang] || i18nFallback.zh;
  setText('w-card-title', dict.title);
  setText('w-badge', isRainMode ? dict.badgeRain : dict.badge);
  setText('w-shift-time', dict.shiftTime);
  setText('w-time-val', dict.timeVal);
  setText('w-van', dict.van);
  setText('w-van-val', dict.vanVal);
  setText('w-location', dict.location);
  setText('w-loc-val', isRainMode ? dict.locIndoor : dict.locFarm);
  setText('w-task', dict.task);
  setText('w-task-val', isRainMode ? dict.taskIndoor : dict.taskFarm);
  setText('w-safety', dict.safety + '：');
  setText('w-safety-val', isRainMode ? dict.safetyIndoor : dict.safetyFarm);
  setText('w-btn-clockin', dict.btnClockIn);
  setText('w-salary-title', dict.salaryTitle);
  setText('w-base-pay', dict.basePay);
  setText('w-bonus-agri', dict.bonusAgri);
  setText('w-bonus-val', dict.bonusVal);
  setText('w-transport-sub', dict.transportSub);
  setText('w-total-est', dict.totalEst);
}

// 7. GPS 打卡
function handleClockIn() {
  const btn = document.getElementById('w-btn-clockin');
  if (btn) {
    btn.disabled = true;
    btn.innerHTML = `<span class="inline-block animate-spin">⏳</span> 定位打卡中...`;
  }

  // 原生 Android 橋接優先
  if (window.AgriNativeBridge && typeof window.AgriNativeBridge.requestGpsLocation === 'function') {
    window.onNativeGpsCallback = function(res) {
      finishClockIn(`✅ 打卡成功！GPS: 桃園八德 (${res.latitude?.toFixed(4) || '24.9348'}, ${res.longitude?.toFixed(4) || '121.2868'}) 時間: ${new Date().toLocaleTimeString()}`);
      if (typeof window.AgriNativeBridge.vibrate === 'function') {
        window.AgriNativeBridge.vibrate(200);
      }
    };
    window.AgriNativeBridge.requestGpsLocation("onNativeGpsCallback");
    return;
  }

  // Web Geolocation 回退
  setTimeout(() => {
    finishClockIn(`✅ 打卡成功！GPS 定位: 桃園市八德區蔬菜產銷班 (時間: ${new Date().toLocaleTimeString()})`);
  }, 600);
}

function finishClockIn(msg) {
  const btn = document.getElementById('w-btn-clockin');
  if (btn) {
    btn.disabled = false;
    btn.className = "w-full py-3 rounded-xl bg-emerald-700 text-white font-bold text-sm shadow-md flex items-center justify-center gap-2 cursor-default";
    btn.innerHTML = msg;
  }
  showNotification(msg);
}

// 8. 天候避險切換
async function toggleWeatherMode() {
  isRainMode = !isRainMode;

  try {
    await fetch(`${API_BASE}/dispatch/weather-switch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ isRainMode })
    });
  } catch (e) {}

  if (window.AgriNativeBridge && typeof window.AgriNativeBridge.vibrate === 'function') {
    window.AgriNativeBridge.vibrate(500);
  }

  updateWorkerLang(currentLang);
  const banner = document.getElementById('weather-status-banner');
  const btn = document.getElementById('weather-toggle-btn');

  if (isRainMode) {
    if (banner) {
      banner.className = "p-4 rounded-xl bg-blue-900/90 text-white border border-blue-400/50 shadow-lg shadow-blue-900/30 transition-all-300";
      banner.innerHTML = `
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="text-3xl animate-bounce">🌧️</span>
            <div>
              <div class="flex items-center gap-2">
                <span class="px-2 py-0.5 text-xs font-bold bg-blue-500 text-white rounded-full">北部梅雨/颱風警報啟動</span>
                <span class="text-xs text-blue-200">桃園八德區 強降雨 23°C</span>
              </div>
              <p class="font-bold text-lg mt-0.5">田區泥濘停工 ➔ 智農 1 號專車即刻改派『八德蔬果預冷冷鏈包裝中心』！</p>
            </div>
          </div>
          <div class="text-right">
            <span class="text-xs text-blue-200 block">避險保全營收</span>
            <span class="text-emerald-400 font-black text-xl">NT$ 62,500 (100% 出勤)</span>
          </div>
        </div>
      `;
    }
    if (btn) {
      btn.className = "px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-sm font-bold shadow-md transition flex items-center gap-2";
      btn.innerHTML = `<span>☀️</span> 恢復晴朗天候（切換回田間溫室排程）`;
    }
    showNotification("⚡ 避險啟動：專車已將田間工班全數轉派室內八德冷鏈包裝線！保全 NT$ 62,500 營收！");
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
    if (btn) {
      btn.className = "px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-500 text-white text-sm font-bold shadow-md transition flex items-center gap-2";
      btn.innerHTML = `<span>🌧️</span> 模擬突發暴雨（啟動冷鏈避險機制）`;
    }
    showNotification("☀️ 天候恢復良好，工班返回八德溫室正常作業！");
  }
}

// 輔助函式
function setText(id, text) {
  const el = document.getElementById(id);
  if (el) el.innerText = text;
}

function showNotification(msg) {
  const toast = document.createElement('div');
  toast.className = "fixed bottom-6 right-6 z-50 bg-slate-800 text-white px-5 py-3 rounded-2xl border border-emerald-500/50 shadow-2xl flex items-center gap-3 text-sm animate-bounce";
  toast.innerHTML = `<span>🌱</span> <span>${msg}</span>`;
  document.body.appendChild(toast);
  setTimeout(() => { toast.remove(); }, 4000);
}
