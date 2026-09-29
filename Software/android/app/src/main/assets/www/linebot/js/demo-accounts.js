/**
 * 智農派工 (AgriDispatch) - 示範帳號清單
 * 僅供 demo 模式（demo/ 與 android www/）使用；同步腳本不會把本檔複製到 web/。
 * production 版一律使用 /api/auth/login 真實帳密驗證，不含此檔、不顯示任何示範密碼提示。
 */
window.AGRI_DEMO_ACCOUNTS = {
  admin: {
    bannerNotice: 'Demo 展示模式：已開通免密通行',
    bannerHint: '免控管帳密，任意輸入或點擊均可進入實測（正式帳密由後端控管）',
    directLinkLabel: '直接免密測試 ➔',
    profiles: [
      { key: 'tech', email: 'admin@agridispatch.tw', password: 'tech2026', org: 'tech', otp: '888888', name: '林技術長 (Tech Lead)', label: '💻 科技技術長' },
      { key: 'dispatcher', email: 'dispatch@coop.tw', password: 'coop888', org: 'coop', otp: '888888', name: '陳理事長 (Coop Chair)', label: '🌾 合作社調度長' },
      { key: 'auditor', email: 'audit@moa.gov.tw', password: 'audit999', org: 'moa', otp: '888888', name: '張稽核專員', label: '🛡️ 農業部稽核員' }
    ]
  },
  farmer: {
    bannerNotice: 'Demo 展示模式：已開通免密通行',
    idPlaceholder: '如 farmer 或 0912-345-678 (Demo免密)',
    pwdPlaceholder: '請輸入密碼 (預設 123456 / Demo免密)',
    pwdHint: '展示密碼：123456 (免驗證)',
    defaultIdentifier: 'farmer',
    defaultPassword: '123456',
    profiles: [
      { identifier: 'farmer', password: '123456', name: '陳建龍 (阿龍班長)', desc: '桃園八德有機蔬菜班', tag: '契作 5.0 分地', accent: 'emerald' },
      { identifier: 'farmer_yilan', password: '123456', name: '李春發 (春發伯)', desc: '宜蘭三星青蔥專班', tag: '契作 8.2 分地', accent: 'teal' }
    ]
  },
  workerForeign: {
    bannerNotice: 'Demo 展示模式：已開通免密通行',
    defaultIdentifier: 'AD88123451',
    defaultPassword: '123456',
    pwdHintByLang: {
      zh: '請輸入密碼 (預設 123456)',
      vi: 'Nhập mật khẩu (Mặc định 123456)',
      id: 'Masukkan kata sandi (Default 123456)',
      th: 'กรอกรหัสผ่าน (ค่าเริ่มต้น 123456)',
      en: 'Enter password (Default 123456)'
    },
    profiles: [
      { identifier: 'AD88123451', password: '123456', workerId: 'W001', flag: '🇻🇳', name: '阮文雄 (A組長)' },
      { identifier: 'AD77234561', password: '123456', workerId: 'W006', flag: '🇮🇩', name: 'Budi (B組長)' },
      { identifier: 'AD66345671', password: '123456', workerId: 'W020', flag: '🇹🇭', name: 'Somchai (D組)' },
      { identifier: 'AD55456781', password: '123456', workerId: 'W023', flag: '🇵🇭', name: 'Maria (B組)' }
    ]
  },
  workerLocal: {
    defaultPassword: '123456',
    pwdPlaceholder: '請輸入密碼 (預設 123456)',
    pwdHint: '預設密碼：123456',
    profiles: [
      { identifier: '0918-668-999', password: '123456', workerId: 'W026', track: 'foreman', icon: '👨‍💼', name: '林小祥 (外展帶隊領班)', desc: '帶領 6 名外展移工 ╳ 枋山芒果採收 (B組小工頭)', tag: 'Tier 2 帶隊領班', tagColor: 'amber' },
      { identifier: '0988-333-444', password: '123456', workerId: 'W012', track: 'machinery', icon: '🚜', name: '陳建宏 (專業機耕師)', desc: '大疆 T50 巡檢代噴 ╳ 溫室曳引機 (C組)', tag: 'Tier 3 專業代耕', tagColor: 'blue' },
      { identifier: '0933-555-666', password: '123456', workerId: 'W025', track: 'machinery', icon: '🚜', name: '林志成 (資深機耕師)', desc: '80 馬力曳引機 ╳ 深耕整地輪作 (C組)', tag: 'Tier 3 專業代耕', tagColor: 'cyan' },
      { identifier: '0912-345-678', password: '123456', workerId: 'W030', track: 'labor', icon: '🧑‍🌾', name: '張志明 (農業勞務工)', desc: '屏東枋山芒果採收 ╳ 合作社在籍農業勞務員', tag: '農業勞務員', tagColor: 'green' }
    ]
  },
  // app.html / vue-crud.html（舊版全景展示頁）沿用的極簡帳密清單
  legacyApp: {
    farmer: { username: 'farmer', password: '123456' },
    worker_foreign: { username: 'worker_foreign', password: '123456' },
    worker_local: { username: 'worker_local', password: '123456' },
    admin_tech: { username: 'admin_tech', password: 'admin888' }
  }
};
