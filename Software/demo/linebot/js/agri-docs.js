/**
 * 智農派工 (AgriDispatch) - 全功能金流收銀台與正式單據 (電子發票 / 薪資單) 下載引擎
 * 支援：
 * 1. 綠界 ECPay / LINE Pay / ATM 虛擬帳號 真實收銀台 (對接 /api/payments/checkout)
 * 2. 財政部規範電子發票證明聯 (HTML + 可列印另存 PDF 下載)
 * 3. 勞動部標準外展農工 / 帶工領班 / 農業勞務員薪資清冊 (中/越/印多語對照 + 另存 PDF 下載)
 * 4. 全域伺服器連線狀態感知與離線提示浮條
 *
 * UI（收銀台彈窗、通知 toast）一律使用設計系統 css/agri.css 的 .ad-* 元件；
 * 發票與薪資單是「紙本單據」，維持自己的列印樣式。規範：07_系統分析及文件/04_UI設計系統規範.md
 */

const AgriDocs = (function () {
  const API_BASE = window.location.origin.startsWith('http')
    ? `${window.location.origin}/api`
    : 'http://localhost:3000/api';

  // 共用工具 ----------------------------------------------------------
  const escHtml = (s) => (window.AgriUtil && window.AgriUtil.escapeHtml)
    ? window.AgriUtil.escapeHtml(String(s == null ? '' : s))
    : String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // 通知文字不放 emoji：各頁舊的呼叫端還帶著，統一在這裡濾掉
  const EMOJI_RE = /(?:\p{Extended_Pictographic}[\uFE0F\u200D]*)+/gu;
  const stripEmoji = (s) => String(s == null ? '' : s).replace(EMOJI_RE, '').replace(/[ \t]{2,}/g, ' ').replace(/^\s+/, '');

  // 確保設計系統樣式已載入（各頁都載入本檔，但不一定載入 agri.css）
  function ensureStyles() {
    if (document.querySelector('link[href*="agri.css"]')) return;
    const me = document.querySelector('script[src*="agri-docs.js"]');
    const href = me ? me.getAttribute('src').replace(/js\/agri-docs\.js.*$/, 'css/agri.css') : 'css/agri.css';
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = href;
    document.head.appendChild(link);
  }
  ensureStyles();

  // 1. 下載電子發票 (財政部電子發票載具規範)
  function downloadInvoice(orderId, customOptions = {}) {
    const invNo = customOptions.invoiceNo || 'AG-' + Math.floor(10000000 + Math.random() * 90000000);
    const dateStr = customOptions.date || new Date().toISOString().slice(0, 10);
    const timeStr = customOptions.time || new Date().toLocaleTimeString('zh-TW', { hour12: false });
    const amount = customOptions.amount || 22400;
    const deposit = customOptions.deposit || Math.round(amount * 0.2);
    const buyerName = customOptions.buyerName || '陳建龍 (八德有機蔬菜產銷班)';
    const cropName = customOptions.cropName || '有機溫室蔬菜 / 高接梨採收派工';

    // 雙軌分流精算：合作社勞務免稅，科技公司代管應稅。
    // 代管費率 16%（財務模型 F2 v2.4；v2.3 以前為 18%／82%）；費率須經合作社依章程決議【待確認】
    const TECH_RATE = 0.16;
    const techPct = Math.round(TECH_RATE * 100);
    const coopPct = 100 - techPct;
    const techAmount = Math.round(amount * TECH_RATE);
    const coopAmount = amount - techAmount;
    const techAmountTaxExcluded = Math.round(techAmount / 1.05);
    const techTax = techAmount - techAmountTaxExcluded;

    const html = `<!DOCTYPE html>
<html lang="zh-TW">
<head>
  <meta charset="UTF-8">
  <title>電子發票證明聯 - ${invNo}</title>
  <style>
    body { font-family: "PingFang TC", "Microsoft JhengHei", sans-serif; background: #f3f4f6; padding: 30px; margin: 0; color: #111827; }
    .invoice-card { max-width: 580px; margin: 0 auto; background: #fff; border: 2px dashed #9ca3af; padding: 28px; border-radius: 12px; box-shadow: 0 10px 25px rgba(0,0,0,0.1); }
    .header { text-align: center; border-bottom: 2px solid #10b981; padding-bottom: 12px; margin-bottom: 16px; }
    .header h1 { font-size: 20px; margin: 0 0 4px 0; color: #065f46; }
    .header p { margin: 2px 0; font-size: 12px; color: #4b5563; }
    .inv-title { text-align: center; font-size: 16px; font-weight: 800; margin: 12px 0; letter-spacing: 1px; }
    .inv-num { text-align: center; font-size: 26px; font-family: monospace; font-weight: 900; color: #047857; margin: 4px 0 12px 0; }
    .meta-table { width: 100%; font-size: 12px; margin-bottom: 14px; border-collapse: collapse; }
    .meta-table td { padding: 4px 0; }
    .items-table { width: 100%; font-size: 12px; border-collapse: collapse; margin-bottom: 16px; }
    .items-table th { background: #ecfdf5; border-bottom: 2px solid #059669; padding: 6px; text-align: left; }
    .items-table td { border-bottom: 1px solid #e5e7eb; padding: 8px 6px; }
    .tax-split-box { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px; font-size: 11px; margin-bottom: 16px; line-height: 1.6; }
    .barcode-area { text-align: center; margin: 18px 0; }
    .barcode-mock { display: inline-block; width: 85%; height: 38px; background: repeating-linear-gradient(90deg, #111, #111 2px, #fff 2px, #fff 4px, #111 4px, #111 6px, #fff 6px, #fff 9px); border-radius: 4px; }
    .qr-area { display: flex; justify-content: space-around; align-items: center; margin: 12px 0; }
    .qr-box { width: 90px; height: 90px; background: #e5e7eb; border: 1px solid #9ca3af; display: flex; align-items: center; justify-content: center; font-size: 10px; color: #4b5563; border-radius: 6px; font-family: monospace; }
    .footer { text-align: center; font-size: 10px; color: #6b7280; border-top: 1px dashed #d1d5db; padding-top: 12px; }
    .btn-print { display: block; width: 100%; max-width: 580px; margin: 20px auto 0 auto; padding: 12px; background: #059669; color: #fff; text-align: center; font-weight: bold; border-radius: 8px; border: none; cursor: pointer; font-size: 14px; }
    .btn-print:hover { background: #047857; }
    @media print {
      body { background: #fff; padding: 0; }
      .invoice-card { box-shadow: none; border: 1px solid #000; }
      .btn-print { display: none; }
    }
  </style>
</head>
<body>
  <div class="invoice-card">
    <div class="header">
      <h1>有限責任台灣智慧農業勞務生產合作社</h1>
      <p>技術代管營運：智農派工科技股份有限公司 (統編：公司設立登記後填入)</p>
      <p>地址：桃園市八德區興豐路 1234 號 ╳ 電話：(03) 388-1234</p>
    </div>

    <div class="inv-title">電子發票證明聯 (營業人銷貨專用)</div>
    <div class="inv-num">${invNo}</div>

    <table class="meta-table">
      <tr>
        <td><strong>開立日期：</strong>${dateStr} ${timeStr}</td>
        <td style="text-align:right;"><strong>防偽隨機碼：</strong>8892</td>
      </tr>
      <tr>
        <td><strong>買受人：</strong>${buyerName}</td>
        <td style="text-align:right;"><strong>訂單編號：</strong>${orderId || 'ORD-2026-002'}</td>
      </tr>
    </table>

    <table class="items-table">
      <thead>
        <tr>
          <th>品名 / 服務項目</th>
          <th style="text-align:right;">單價</th>
          <th style="text-align:center;">數量</th>
          <th style="text-align:right;">金額</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>${cropName} (早鳥工時包)</td>
          <td style="text-align:right;">NT$ ${amount.toLocaleString()}</td>
          <td style="text-align:center;">1 式</td>
          <td style="text-align:right;">NT$ ${amount.toLocaleString()}</td>
        </tr>
        <tr>
          <td colspan="3" style="text-align:right;"><strong>履約定金 (20% 已付)：</strong></td>
          <td style="text-align:right; color:#059669; font-weight:bold;">NT$ ${deposit.toLocaleString()}</td>
        </tr>
        <tr>
          <td colspan="3" style="text-align:right;"><strong>待完工尾款 (80%)：</strong></td>
          <td style="text-align:right; font-weight:bold;">NT$ ${(amount - deposit).toLocaleString()}</td>
        </tr>
      </tbody>
    </table>

    <div class="tax-split-box">
      <strong>合作社與科技公司雙軌金流扣繳拆分：</strong><br>
      • 合作社外展農務勞務（免營業稅，農民社員交易）：<strong>NT$ ${coopAmount.toLocaleString()}</strong> (${coopPct}%)<br>
      • 智農科技代管營運（專車 logistics 與系統代管，應稅 5%）：<strong>NT$ ${techAmount.toLocaleString()}</strong> (銷售額 $${techAmountTaxExcluded.toLocaleString()} / 稅額 $${techTax.toLocaleString()})<br>
      • 備註：本頁為系統展示用發票範本，並非正式統一發票；正式營運須串接財政部電子發票整合服務平台開立。
    </div>

    <div class="barcode-area">
      <div class="barcode-mock"></div>
      <div style="font-family:monospace; font-size:10px; color:#4b5563; margin-top:3px;">11509${invNo.replace('-', '')}8892</div>
    </div>

    <div class="qr-area">
      <div class="qr-box">[ 財政部驗證 QR-L ]</div>
      <div class="qr-box">[ 合作社防偽 QR-R ]</div>
    </div>

    <div class="footer">
      感謝您支持台灣智慧農業派工！如有發票載具歸戶或報帳疑問，請洽本平台客服專線。
    </div>
  </div>

  <button class="btn-print" onclick="window.print()">列印此發票 / 另存為 PDF</button>
</body>
</html>`;

    // 觸發實體檔案下載
    const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `智農派工_電子發票_${invNo}.html`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);

    // 同步開啟列印/PDF 預覽新分頁
    const printWindow = window.open('', '_blank');
    if (printWindow) {
      printWindow.document.write(html);
      printWindow.document.close();
    }
  }

  // 2. 下載標準外展農工 / 領班 / 勞務工薪資單 (勞基法第23條工資清冊規範)
  function downloadPayslip(month = '2026年08月', workerType = 'foreign', customData = {}) {
    const isForeign = workerType === 'foreign';
    const isForeman = workerType === 'foreman';
    const isLabor = workerType === 'labor';

    let workerName = customData.name || (isForeign ? '阮文雄 (Nguyễn Văn Hùng)' : (isForeman ? '林小祥 (帶工領班)' : '張志明 (農業勞務工)'));
    let workerId = customData.id || (isForeign ? 'W001' : (isForeman ? 'W026' : 'W030'));
    let baseSalary = isForeman ? 42000 : 29500;
    let agriBonus = 8800; // 農業部中央外展就業獎勵金
    let foremanAllowance = isForeman ? 8000 : 0;
    let transportAllowance = isForeman ? 2800 : (isLabor ? 2400 : 1200);
    let overtimePay = isForeman ? 3600 : 2480;

    let grossPay = baseSalary + agriBonus + foremanAllowance + transportAllowance + overtimePay;

    // 法定扣除額
    let laborIns = 629;
    let healthIns = 443;
    let dormFee = isForeign ? 3000 : 0; // 外展移工集中宿舍上限代扣
    let totalDeduction = laborIns + healthIns + dormFee;
    let netPay = grossPay - totalDeduction;

    const html = `<!DOCTYPE html>
<html lang="zh-TW">
<head>
  <meta charset="UTF-8">
  <title>外展農工工資清冊明細表 - ${month} - ${workerName}</title>
  <style>
    body { font-family: "PingFang TC", "Microsoft JhengHei", sans-serif; background: #0f172a; padding: 25px; margin: 0; color: #f8fafc; }
    .slip-card { max-width: 650px; margin: 0 auto; background: #1e293b; border: 1px solid #334155; padding: 26px; border-radius: 16px; box-shadow: 0 15px 35px rgba(0,0,0,0.4); }
    .header { text-align: center; border-bottom: 2px solid #3b82f6; padding-bottom: 12px; margin-bottom: 16px; }
    .header h1 { font-size: 18px; margin: 0 0 4px 0; color: #60a5fa; }
    .header p { margin: 2px 0; font-size: 11px; color: #94a3b8; }
    .badge { display: inline-block; padding: 3px 8px; border-radius: 9999px; background: rgba(59,130,246,0.2); border: 1px solid rgba(59,130,246,0.4); color: #93c5fd; font-size: 11px; font-weight: bold; margin-top: 4px; }
    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; background: #0f172a; padding: 12px; border-radius: 10px; margin-bottom: 16px; font-size: 12px; border: 1px solid #334155; }
    .table-section { width: 100%; border-collapse: collapse; font-size: 12px; margin-bottom: 16px; }
    .table-section th { background: #334155; padding: 8px; text-align: left; color: #e2e8f0; }
    .table-section td { padding: 8px; border-bottom: 1px solid #334155; }
    .total-box { background: linear-gradient(135deg, rgba(16,185,129,0.15), rgba(5,150,105,0.1)); border: 1px solid #10b981; border-radius: 12px; padding: 14px; text-align: center; margin-bottom: 16px; }
    .total-title { font-size: 12px; color: #6ee7b7; font-weight: bold; }
    .total-amount { font-size: 28px; font-family: monospace; font-weight: 900; color: #34d399; margin: 4px 0; }
    .multilingual-note { font-size: 11px; background: #0f172a; padding: 10px; border-radius: 8px; border-left: 3px solid #8b5cf6; color: #cbd5e1; line-height: 1.5; margin-bottom: 14px; }
    .footer { text-align: center; font-size: 10px; color: #64748b; border-top: 1px solid #334155; padding-top: 12px; }
    .btn-print { display: block; width: 100%; max-width: 650px; margin: 20px auto 0 auto; padding: 12px; background: #2563eb; color: #fff; text-align: center; font-weight: bold; border-radius: 8px; border: none; cursor: pointer; font-size: 14px; }
    .btn-print:hover { background: #1d4ed8; }
    @media print {
      body { background: #fff; color: #000; padding: 0; }
      .slip-card { background: #fff; border: 1px solid #000; box-shadow: none; color: #000; }
      .info-grid { background: #f8fafc; border: 1px solid #ccc; color: #000; }
      .table-section th { background: #eee; color: #000; }
      .table-section td { border-bottom: 1px solid #ccc; color: #000; }
      .total-box { background: #e6fffa; border: 1px solid #000; color: #000; }
      .total-amount { color: #000; }
      .multilingual-note { background: #f5f3ff; color: #000; border-left-color: #6b21a8; }
      .btn-print { display: none; }
    }
  </style>
</head>
<body>
  <div class="slip-card">
    <div class="header">
      <h1>有限責任台灣智慧農業勞務生產合作社</h1>
      <p>外展農務就業人員個人工資清冊明細 (符合勞動基準法第 23 條規範)</p>
      <div class="badge">薪資核發月份：${month}</div>
    </div>

    <div class="info-grid">
      <div><strong>農工姓名：</strong>${workerName}</div>
      <div><strong>員工工號：</strong><span style="font-family:monospace;">${workerId}</span></div>
      <div><strong>職責身分：</strong>${isForeman ? '本國帶工領班 (小工頭)' : (isLabor ? '合作社在籍農業勞務員' : '外籍外展農工')}</div>
      <div><strong>發薪日期：</strong>2026/09/05 (電匯入帳)</div>
      <div><strong>所屬調度工班：</strong>工班 A 組 (桃園八德 / 屏東枋山)</div>
      <div><strong>勞健保與職災：</strong>100% 依法足額提繳</div>
    </div>

    <table class="table-section">
      <thead>
        <tr>
          <th>應發薪酬項目 (Earnings)</th>
          <th style="text-align:right;">金額 (NT$)</th>
          <th>多語說明 (Vi / Id)</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>保障底薪 (Base Salary)</td>
          <td style="text-align:right; font-family:monospace; font-weight:bold;">$${baseSalary.toLocaleString()}</td>
          <td style="font-size:10px; color:#94a3b8;">Lương cơ bản / Gaji Pokok</td>
        </tr>
        <tr>
          <td>農業部中央就業獎勵津貼</td>
          <td style="text-align:right; font-family:monospace; color:#34d399; font-weight:bold;">+$${agriBonus.toLocaleString()}</td>
          <td style="font-size:10px; color:#94a3b8;">Thưởng việc làm Bộ NN / Bonus Pertanian</td>
        </tr>
        ${foremanAllowance > 0 ? `
        <tr>
          <td>外展帶工小工頭責任津貼</td>
          <td style="text-align:right; font-family:monospace; color:#fbbf24; font-weight:bold;">+$${foremanAllowance.toLocaleString()}</td>
          <td style="font-size:10px; color:#94a3b8;">Phụ cấp tổ trưởng / Tunjangan Mandor</td>
        </tr>` : ''}
        <tr>
          <td>專車交通與高溫出勤津貼</td>
          <td style="text-align:right; font-family:monospace; font-weight:bold;">+$${transportAllowance.toLocaleString()}</td>
          <td style="font-size:10px; color:#94a3b8;">Trợ cấp đi lại / Tunjangan Transport</td>
        </tr>
        <tr>
          <td>延長工作時間加班費 (12小時)</td>
          <td style="text-align:right; font-family:monospace; font-weight:bold;">+$${overtimePay.toLocaleString()}</td>
          <td style="font-size:10px; color:#94a3b8;">Tiền làm thêm giờ / Uang Lembur</td>
        </tr>
        <tr style="background:rgba(255,255,255,0.03);">
          <td><strong>應發薪酬總額 (Gross Salary)</strong></td>
          <td style="text-align:right; font-family:monospace; font-weight:900;">$${grossPay.toLocaleString()}</td>
          <td></td>
        </tr>
      </tbody>
    </table>

    <table class="table-section">
      <thead>
        <tr>
          <th>法定代扣項目 (Deductions)</th>
          <th style="text-align:right;">金額 (NT$)</th>
          <th>法規說明</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>勞工保險自付額 (Labor Insurance)</td>
          <td style="text-align:right; font-family:monospace; color:#f87171;">-$${laborIns}</td>
          <td style="font-size:10px; color:#94a3b8;">普通事故+就保自付 20%</td>
        </tr>
        <tr>
          <td>全民健康保險自付額 (Health Insurance)</td>
          <td style="text-align:right; font-family:monospace; color:#f87171;">-$${healthIns}</td>
          <td style="font-size:10px; color:#94a3b8;">第一類被保險人自付 30%</td>
        </tr>
        ${dormFee > 0 ? `
        <tr>
          <td>集中宿舍膳宿與水電費代扣</td>
          <td style="text-align:right; font-family:monospace; color:#f87171;">-$${dormFee.toLocaleString()}</td>
          <td style="font-size:10px; color:#94a3b8;">中央勞動契約規範上限 NT$3,000</td>
        </tr>` : ''}
        <tr style="background:rgba(255,255,255,0.03);">
          <td><strong>代扣總額 (Total Deductions)</strong></td>
          <td style="text-align:right; font-family:monospace; color:#f87171; font-weight:bold;">-$${totalDeduction.toLocaleString()}</td>
          <td></td>
        </tr>
      </tbody>
    </table>

    <div class="total-box">
      <div class="total-title">本月銀行實撥淨額 (NET SALARY TRANSFERRED)</div>
      <div class="total-amount">NT$ ${netPay.toLocaleString()}</div>
      <div style="font-size:11px; color:#94a3b8;">電匯帳號：中華郵政 (700) 帳號末五碼 56789 ╳ 匯入完成</div>
    </div>

    <div class="multilingual-note">
      <strong>🇻🇳 Lưu ý cho người lao động:</strong> Tiền lương tháng này đã chuyển vào tài khoản bưu điện của bạn kèm khoản tiền thưởng việc làm NT$ 8,800. Nếu có thắc mắc vui lòng liên hệ quản lý ký túc xá.<br>
      <strong>🇮🇩 Catatan untuk pekerja:</strong> Gaji bulan ini telah ditransfer ke rekening pos Anda termasuk bonus pertanian NT$ 8.800 dari Kementerian Pertanian.
    </div>

    <div class="footer">
      合作社出納印鑑：[ 台灣智農合作社核訖 ] ╳ 合作社立案字號：農授糧字第 1150092300 號
    </div>
  </div>

  <button class="btn-print" onclick="window.print()">列印此薪資單 / 另存為 PDF</button>
</body>
</html>`;

    // 觸發實體檔案下載
    const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `智農派工_外展農工薪資清冊_${month}_${workerId}.html`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);

    // 同步開啟列印/PDF 預覽新分頁
    const printWindow = window.open('', '_blank');
    if (printWindow) {
      printWindow.document.write(html);
      printWindow.document.close();
    }
  }

  // 3. 實體互動金流收銀台 (ECPay / LINE Pay / ATM)
  function openPaymentCashier(orderId, amount, cropName, farmLocation, onSuccessCallback) {
    ensureStyles();
    const existingModal = document.getElementById('agriCheckoutModal');
    if (existingModal) existingModal.remove();

    const depositAmount = amount || 4480;
    const oid = orderId || 'ORD-2026-002';
    const isDemo = !window.AGRI_CONFIG || window.AGRI_CONFIG.mode !== 'production';
    const due = new Date(Date.now() + 3 * 86400000);
    const dueText = `${due.getFullYear()}/${String(due.getMonth() + 1).padStart(2, '0')}/${String(due.getDate()).padStart(2, '0')} 23:59 止`;
    const testCard = isDemo ? { num: '4311-9522-2222-2222', exp: '12/28', cvv: '888' } : { num: '', exp: '', cvv: '' };

    const modalHtml = `
    <div id="agriCheckoutModal" class="ad-modal" role="dialog" aria-modal="true" aria-labelledby="agriCheckoutTitle">
      <div class="ad-modal__panel">
        <div class="ad-card__head">
          <div>
            <h3 id="agriCheckoutTitle" class="ad-title">支付早鳥定金</h3>
            <p class="ad-caption ad-num">訂單號 ${escHtml(oid)}</p>
          </div>
          <button type="button" data-ad-close class="ad-btn ad-btn--ghost ad-btn--icon ad-btn--sm" aria-label="關閉">×</button>
        </div>

        <div class="ad-card ad-card--inset" style="display: flex; align-items: center; justify-content: space-between; gap: 12px;">
          <div style="min-width: 0;">
            <div class="ad-label">派工田區與品項</div>
            <div class="ad-heading" style="margin-top: 2px;">${escHtml(cropName || '苗栗卓蘭高接梨 (4分地)')}</div>
          </div>
          <div style="text-align: right; flex-shrink: 0;">
            <div class="ad-label">應付定金（20%）</div>
            <div class="ad-price ad-price--lg ad-price--accent">NT$ ${Number(depositAmount).toLocaleString()}</div>
          </div>
        </div>

        <div>
          <div class="ad-label" style="margin-bottom: 6px;">付款方式</div>
          <div class="ad-seg" role="group" aria-label="付款方式" style="display: flex;">
            <button type="button" id="payTab_card" class="ad-seg__btn is-active" style="flex: 1;" onclick="AgriDocs._selectPayTab('card')">信用卡</button>
            <button type="button" id="payTab_line" class="ad-seg__btn" style="flex: 1;" onclick="AgriDocs._selectPayTab('line')">LINE Pay</button>
            <button type="button" id="payTab_atm" class="ad-seg__btn" style="flex: 1;" onclick="AgriDocs._selectPayTab('atm')">ATM 轉帳</button>
          </div>
        </div>

        <div id="payContent_card">
          <div class="ad-card ad-card--inset" style="display: flex; flex-direction: column; gap: 12px;">
            <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px;">
              <span class="ad-caption">綠界科技 ECPay 3D 驗證環境</span>
              ${isDemo ? '<button type="button" class="ad-btn ad-btn--ghost ad-btn--sm" onclick="AgriDocs._fillTestCard()">填入測試卡號</button>' : ''}
            </div>
            <div class="ad-field">
              <label class="ad-label" for="input_card_num">信用卡卡號（16 碼）</label>
              <input id="input_card_num" class="ad-input ad-num" type="text" inputmode="numeric" autocomplete="cc-number" value="${testCard.num}">
            </div>
            <div class="ad-form-grid ad-form-grid--2">
              <div class="ad-field">
                <label class="ad-label" for="input_card_exp">有效月年（MM/YY）</label>
                <input id="input_card_exp" class="ad-input ad-num" type="text" inputmode="numeric" autocomplete="cc-exp" value="${testCard.exp}">
              </div>
              <div class="ad-field">
                <label class="ad-label" for="input_card_cvv">背面末三碼（CVV）</label>
                <input id="input_card_cvv" class="ad-input ad-num" type="text" inputmode="numeric" autocomplete="cc-csc" value="${testCard.cvv}">
              </div>
            </div>
          </div>
        </div>

        <div id="payContent_line" hidden>
          <div class="ad-card ad-card--inset" style="text-align: center;">
            <div class="ad-heading" style="margin-bottom: 12px;">LINE Pay 快速條碼結帳</div>
            <div style="width: 112px; height: 112px; margin: 0 auto; padding: 8px; background: #fff; border-radius: 8px; display: flex; align-items: center; justify-content: center;">
              <div style="font-family: monospace; font-size: 10px; color: #111; word-break: break-all; text-align: center;">
                [ LINE PAY QR ]<br>NT$ ${depositAmount}<br>${escHtml(oid)}
              </div>
            </div>
            <p class="ad-caption" style="margin-top: 12px;">用手機 LINE「錢包」掃描上方條碼即可授權付款。</p>
          </div>
        </div>

        <div id="payContent_atm" hidden>
          <div class="ad-card ad-card--inset" style="display: flex; flex-direction: column; gap: 8px;">
            <div class="ad-heading">台灣 Pay／跨行虛擬轉帳帳號</div>
            <div style="display: flex; justify-content: space-between; gap: 12px;"><span class="ad-caption">收款行庫</span><span class="ad-num" style="font-weight: 600;">013 國泰世華商業銀行</span></div>
            <div style="display: flex; justify-content: space-between; gap: 12px;"><span class="ad-caption">專屬繳款帳號</span><span class="ad-num" style="font-weight: 700; color: var(--ad-accent-text);">9812-0923-4480-1288</span></div>
            <div style="display: flex; justify-content: space-between; gap: 12px;"><span class="ad-caption">繳費期限</span><span class="ad-num" style="font-weight: 600;">${dueText}</span></div>
          </div>
        </div>

        <div id="checkoutFeedbackBox" role="status" hidden></div>

        <div class="ad-card__foot" style="margin-top: 0;">
          <button type="button" data-ad-close class="ad-btn ad-btn--ghost">取消</button>
          <button id="btnSubmitPayment" type="button" class="ad-btn ad-btn--accent"><span id="btnSubmitPaymentText">確認支付 NT$ ${Number(depositAmount).toLocaleString()}</span></button>
        </div>
      </div>
    </div>
    `;

    document.body.insertAdjacentHTML('beforeend', modalHtml);
    window._onPaymentSuccess = onSuccessCallback;

    const modal = document.getElementById('agriCheckoutModal');
    const close = () => { modal.remove(); document.removeEventListener('keydown', onKey); };
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    modal.querySelectorAll('[data-ad-close]').forEach((b) => b.addEventListener('click', close));
    modal.addEventListener('click', (e) => { if (e.target === modal) close(); });
    document.addEventListener('keydown', onKey);
    document.getElementById('btnSubmitPayment').addEventListener('click', () => _doSubmitPayment(oid, depositAmount));
  }

  function _selectPayTab(tab) {
    ['card', 'line', 'atm'].forEach((t) => {
      const btn = document.getElementById(`payTab_${t}`);
      const content = document.getElementById(`payContent_${t}`);
      if (btn) btn.classList.toggle('is-active', t === tab);
      if (content) content.hidden = (t !== tab);
    });
  }

  function _fillTestCard() {
    document.getElementById('input_card_num').value = '4311-9522-2222-2222';
    document.getElementById('input_card_exp').value = '12/28';
    document.getElementById('input_card_cvv').value = '888';
  }

  async function _doSubmitPayment(orderId, amount) {
    const btn = document.getElementById('btnSubmitPayment');
    const btnText = document.getElementById('btnSubmitPaymentText');
    const feedback = document.getElementById('checkoutFeedbackBox');

    btn.disabled = true;
    btnText.innerText = '授權處理中…';

    const isDemo = !window.AGRI_CONFIG || window.AGRI_CONFIG.mode !== 'production';
    const esc = escHtml;
    const setFeedback = (kind, html) => {
      feedback.hidden = false;
      feedback.className = `ad-notice ad-notice--${kind}`;
      feedback.innerHTML = `<div style="min-width: 0;">${html}</div>`;
    };
    const showError = (msg) => {
      setFeedback('danger', `<div style="font-weight: 600;">付款未完成</div><div class="ad-caption" style="color: inherit;">${esc(msg)}</div>`);
      btn.disabled = false;
      btnText.innerText = '重新付款';
    };
    // 後端明確拒絕（例如訂單已取消、無須定金、未登入）時不走離線備援
    class PaymentRejected extends Error {}

    try {
      // 1. 建立待付款交易（金額一律由後端依訂單計算，前端不傳 amount）
      const res = await fetch(`${API_BASE}/payments/checkout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ orderId, paymentMethod: 'credit_card' })
      });
      let data = await res.json().catch(() => ({}));
      if (!res.ok || data.success === false) {
        throw new PaymentRejected(data.error || data.message || `付款失敗（HTTP ${res.status}）`);
      }

      // 2. 依金流供應商完成付款
      if (data.status === 'PENDING' && data.next) {
        if (data.next.actionUrl) {
          // 綠界 ECPay：以 POST 表單導向綠界付款頁，付款結果由後端 /api/payments/ecpay/notify 接收
          btnText.innerText = '前往綠界付款頁…';
          const form = document.createElement('form');
          form.method = data.next.method || 'POST';
          form.action = data.next.actionUrl;
          Object.entries(data.next.params || {}).forEach(([k, v]) => {
            const input = document.createElement('input');
            input.type = 'hidden'; input.name = k; input.value = v;
            form.appendChild(input);
          });
          document.body.appendChild(form);
          form.submit();
          return;
        }
        if (data.next.confirmUrl) {
          // 模擬金流（僅 demo 模式）：呼叫 confirmUrl 完成付款，不會實際扣款
          const confirmRes = await fetch(API_BASE.replace(/\/api$/, '') + data.next.confirmUrl, { method: 'POST' });
          const confirmData = await confirmRes.json().catch(() => ({}));
          if (!confirmRes.ok || confirmData.success === false) {
            throw new PaymentRejected(confirmData.error || `付款確認失敗（HTTP ${confirmRes.status}）`);
          }
          data = { ...data, ...confirmData };
        }
      }

      setFeedback('ok', `
        <div style="font-weight: 600;">${data.alreadyPaid ? '此訂單定金已付清' : '付款成功'}（交易序號 ${esc(data.transactionId)}）</div>
        <div class="ad-caption" style="color: inherit;">授權碼 <span class="ad-num">${esc(data.authCode || '—')}</span>・電子發票 <span class="ad-num">${esc(data.invoiceNo || '—')}</span></div>
        ${data.provider === 'mock' ? '<div class="ad-caption" style="color: inherit;">展示模式模擬金流，未實際扣款。</div>' : ''}
      `);

      btnText.innerText = '已完成扣款';

      setTimeout(() => {
        const modal = document.getElementById('agriCheckoutModal');
        if (modal) modal.remove();
        if (typeof window._onPaymentSuccess === 'function') {
          window._onPaymentSuccess(data);
        }
      }, 1500);

    } catch (e) {
      if (e instanceof PaymentRejected) {
        showError(e.message);
        return;
      }
      if (!isDemo) {
        showError('無法連線金流伺服器，請稍後再試。');
        return;
      }
      // 本地展示備援（僅 demo 模式、後端離線時）
      console.warn('Backend unavailable, simulating checkout locally', e);
      setFeedback('ok', `
        <div style="font-weight: 600;">付款成功（離線展示交易序號 TXN-20260923-99120）</div>
        <div class="ad-caption" style="color: inherit;">授權核銷碼 <span class="ad-num">AUTH-882103</span>・發票號碼 <span class="ad-num">AG-98765432</span></div>
      `);
      btnText.innerText = '已完成扣款';
      setTimeout(() => {
        const modal = document.getElementById('agriCheckoutModal');
        if (modal) modal.remove();
        if (typeof window._onPaymentSuccess === 'function') {
          window._onPaymentSuccess({ success: true, transactionId: 'TXN-20260923-99120', invoiceNo: 'AG-98765432' });
        }
      }, 1500);
    }
  }

  // 4. 全域伺服器在線健康監控浮條
  function initServerStatusMonitor() {
    const bannerId = 'agriServerStatusBanner';
    const check = async () => {
      let isOk = false;
      try {
        const res = await fetch(`${API_BASE}/crops`, { signal: AbortSignal.timeout(2000) });
        if (res.ok) isOk = true;
      } catch (e) {
        isOk = false;
      }

      let banner = document.getElementById(bannerId);
      if (!isOk) {
        if (!banner) {
          banner = document.createElement('div');
          banner.id = bannerId;
          // 一行靜態提示，不跳動；樣式內嵌，不依賴各頁是否已載入 agri.css
          const isDemo = !(window.AGRI_CONFIG && window.AGRI_CONFIG.mode === 'production');
          banner.setAttribute('role', 'status');
          banner.style.cssText = 'position:fixed;left:50%;bottom:16px;transform:translateX(-50%);z-index:60;max-width:min(92vw,480px);' +
            'display:flex;align-items:center;gap:12px;padding:10px 14px;border-radius:12px;font-size:13px;line-height:1.4;' +
            'background:#1e293b;color:#f1f5f9;border:1px solid #334155;box-shadow:0 1px 2px rgba(0,0,0,.35)';
          banner.innerHTML = `
            <span>${isDemo ? '後端未連線，目前顯示示範資料。' : '伺服器連線中斷，資料可能不是最新。'}</span>
            <button type="button" onclick="AgriDocs.retryServer()" style="flex-shrink:0;min-height:32px;padding:0 12px;border-radius:8px;border:1px solid #475569;background:#0f172a;color:#f1f5f9;font:inherit;font-size:13px;font-weight:600;cursor:pointer">
              重試
            </button>
          `;
          document.body.appendChild(banner);
        }
        // 手機有底部 tabbar 時往上抬，避免蓋住導覽
        const tabbar = document.querySelector('.ad-tabbar');
        const hasTabbar = tabbar && getComputedStyle(tabbar).display !== 'none';
        banner.style.bottom = hasTabbar ? 'calc(72px + env(safe-area-inset-bottom))' : '16px';
      } else {
        if (banner) banner.remove();
      }
    };

    check();
    setInterval(check, 15000);
  }

  function retryServer() {
    window.location.reload();
  }

  // 5. 跨平台推播通知發送器 (Android 原生 Notification / Web Notification / 浮動 Toast)
  function notify(title, message, options = {}) {
    const channel = options.channel || 'dispatch';
    title = stripEmoji(title);
    message = stripEmoji(message);

    // A. 原生 Android 容器
    if (window.AgriNativeBridge) {
      if (typeof window.AgriNativeBridge.sendSystemNotification === 'function') {
        window.AgriNativeBridge.sendSystemNotification(title, message, channel);
      } else if (typeof window.AgriNativeBridge.showToast === 'function') {
        window.AgriNativeBridge.showToast(`${title}: ${message}`);
      }
      if (typeof window.AgriNativeBridge.vibrate === 'function') {
        window.AgriNativeBridge.vibrate(150);
      }
    }

    // B. Web Notification API
    if ('Notification' in window && Notification.permission === 'granted') {
      try {
        new Notification(title, { body: message });
      } catch (ignored) {}
    } else if ('Notification' in window && Notification.permission !== 'denied') {
      Notification.requestPermission().then(permission => {
        if (permission === 'granted') {
          try {
            new Notification(title, { body: message });
          } catch (ignored) {}
        }
      });
    }

    // C. 浮動通知（靜止、不閃、不跳；警示以紅色邊線標示）
    ensureStyles();
    const toastContainerId = 'agriFloatingNotificationContainer';
    let container = document.getElementById(toastContainerId);
    if (!container) {
      container = document.createElement('div');
      container.id = toastContainerId;
      container.className = 'ad-toast-stack';
      container.setAttribute('aria-live', 'polite');
      document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    const isAlert = channel === 'weather' || options.isAlert;
    toast.className = 'ad-toast-item' + (isAlert ? ' ad-toast-item--alert' : '');
    toast.setAttribute('role', isAlert ? 'alert' : 'status');
    toast.innerHTML = `
      <div class="ad-toast-item__body">
        <div class="ad-toast-item__title">${escHtml(title)}</div>
        <div class="ad-toast-item__msg">${escHtml(message)}</div>
      </div>
      <button type="button" class="ad-btn ad-btn--ghost ad-btn--icon ad-btn--sm" aria-label="關閉">×</button>
    `;
    const remove = () => {
      toast.classList.remove('is-in');
      toast.classList.add('is-out');
      setTimeout(() => toast.remove(), 250);
    };
    toast.querySelector('button').addEventListener('click', remove);
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add('is-in'));
    setTimeout(remove, options.duration || 4500);
  }

  // 6. 出勤打卡（真實 API：/api/attendance/clock-in、/clock-out；離線或舊後端時走示範回應）
  async function clockAction(type, options = {}) {
    const isClockIn = type === 'clock-in';
    const nowStr = new Date().toLocaleTimeString('zh-TW');
    const path = isClockIn ? 'clock-in' : 'clock-out';
    const user = (window.AgriAuth && window.AgriAuth.getUser && window.AgriAuth.getUser()) || null;
    // 登入者本人的工號優先（正式版工人只能替自己打卡）；頁面傳入的 workerId 只作 demo 後備
    const workerId = (user && user.refId) || options.workerId || (user && user.username) || 'self';

    let res = null;
    try {
      res = await fetch(`${API_BASE}/attendance/${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          workerId, lat: options.lat, lng: options.lng,
          location: options.locationLabel, timestamp: new Date().toISOString()
        })
      });
    } catch (e) {
      res = null; // 後端離線：走以下離線示範回應
    }

    if (res && res.status !== 404) {
      const data = await res.json().catch(() => null);
      if (res.ok && data && data.success) {
        if (!options.silent) notify(
          isClockIn ? '上班打卡成功' : '下班打卡成功',
          `時間：${nowStr}${data.data && (data.data.workHours || data.data.actualHours) ? `　今日工時：${data.data.workHours || data.data.actualHours} 小時` : ''}`,
          { channel: 'attendance' }
        );
        return data;
      }
      // 後端明確拒絕（例如 409 重複上班打卡、尚未上班即下班）：如實告知，不假裝成功
      const reason = (data && (data.error || data.message)) || `HTTP ${res.status}`;
      notify(isClockIn ? '上班打卡未完成' : '下班打卡未完成', reason, { channel: 'attendance' });
      return { success: false, status: res.status, error: reason };
    }

    const isDemo = !window.AGRI_CONFIG || window.AGRI_CONFIG.mode !== 'production';
    if (!isDemo) {
      notify('打卡失敗', '無法連線伺服器，請稍後再試或聯繫帶班組長。', { channel: 'attendance' });
      return { success: false, offline: true };
    }

    const offlineDetail = isClockIn
      ? `時間：${nowStr}　GPS 定位：${options.locationLabel || '屏東縣枋山鄉 (22.6123°N, 120.6145°E)'}`
      : `時間：${nowStr}　今日工時：${options.demoHours || (Math.floor(Math.random() * 2) + 7)} 小時`;
    if (!options.silent) notify(isClockIn ? '上班打卡成功（離線示範）' : '下班打卡成功（離線示範）', offlineDetail, { channel: 'attendance' });
    return { success: true, offline: true };
  }

  // 7. 緊急求助（真實 API：POST /api/notifications/send；離線時走示範回應）
  async function sendEmergencyAlert(message, options = {}) {
    const user = (window.AgriAuth && window.AgriAuth.getUser && window.AgriAuth.getUser()) || null;
    try {
      const res = await fetch(`${API_BASE}/notifications/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          type: 'emergency',
          message,
          from: (user && (user.name || user.username)) || 'worker',
          contact: options.contact
        })
      });
      if (res.ok) {
        const data = await res.json().catch(() => null);
        if (data && data.success) {
          notify('緊急求助已送出', message, { channel: 'emergency', isAlert: true });
          return data;
        }
      }
    } catch (e) {
      // 後端離線：走以下離線示範回應
    }
    notify('緊急求助已送出（離線示範）', message, { channel: 'emergency', isAlert: true });
    return { success: true, offline: true };
  }

  // 8. GPS 定位顯示（純前端展示，無需 API）
  function showGpsLocation(title, message) {
    notify(title || 'GPS 即時定位', message, { channel: 'gps' });
  }

  return {
    downloadInvoice,
    downloadPayslip,
    openPaymentCashier,
    initServerStatusMonitor,
    retryServer,
    notify,
    clockAction,
    sendEmergencyAlert,
    showGpsLocation,
    _selectPayTab,
    _fillTestCard,
    _doSubmitPayment
  };
})();

// 頂層 const 不會掛到 window：各頁用 `window.AgriDocs ? … : …` 判斷時會誤以為沒載入
// （打卡不打後端、通知與送單錯誤都不顯示），所以明確掛上 window
window.AgriDocs = AgriDocs;

// 當 DOM 載入時自動初始化伺服器狀態監控
document.addEventListener('DOMContentLoaded', () => {
  AgriDocs.initServerStatusMonitor();
});
