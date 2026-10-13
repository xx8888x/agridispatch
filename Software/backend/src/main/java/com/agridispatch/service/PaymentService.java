package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.PaymentTransaction;
import com.agridispatch.repository.PaymentTransactionRepository;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.payment.EcpayPaymentProvider;
import com.agridispatch.service.payment.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 定金金流服務
 * - 金額一律由後端依訂單計算（忽略 client 傳入的 amount）
 * - 冪等：已付款訂單回傳既有交易；同訂單重複結帳沿用同一筆待付款交易
 * - 已取消 / 已完工訂單拒絕付款；定金為 0 的方案（hourly_flex 等）不收定金
 * - provider 抽象：agri.payment.provider = mock | ecpay
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TX_TS = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final char[] ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final OrderService orderService;
    private final PaymentTransactionRepository txRepository;
    private final Map<String, PaymentProvider> providers = new HashMap<>();
    private final EcpayPaymentProvider ecpay;
    private final AgriSettings settings;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();

    public PaymentService(OrderService orderService,
                          PaymentTransactionRepository txRepository,
                          List<PaymentProvider> providerList,
                          EcpayPaymentProvider ecpay,
                          AgriSettings settings) {
        this.orderService = orderService;
        this.txRepository = txRepository;
        this.ecpay = ecpay;
        this.settings = settings;
        for (PaymentProvider p : providerList) providers.put(p.id(), p);
    }

    private PaymentProvider activeProvider() {
        PaymentProvider p = providers.get(settings.getPaymentProvider());
        if (p == null) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "不支援的金流供應者設定：" + settings.getPaymentProvider());
        }
        return p;
    }

    /**
     * 建立定金付款（結帳）
     */
    public Map<String, Object> checkout(AuthContext ctx, String orderId, String paymentMethod, String payerName) {
        if (orderId == null || orderId.trim().isEmpty()) {
            throw ApiException.badRequest("訂單編號 (orderId) 不得為空！");
        }
        synchronized (lock) {
            Order order = orderService.requireOrder(orderId.trim());
            orderService.checkOwnerOrAdmin(ctx, order);

            if (OrderService.CANCELLED.equals(order.getStatus())) {
                throw ApiException.conflict("訂單已取消，無法付款");
            }
            if (order.isDepositPaid()) {
                return paidResponse(order, latestDeposit(order.getId(), PaymentTransaction.PAID), true);
            }
            if (OrderService.COMPLETED.equals(order.getStatus())) {
                throw ApiException.conflict("訂單已完工，無需再支付定金");
            }
            int amount = order.getDepositAmount();
            if (amount <= 0) {
                throw ApiException.badRequest("此方案無需預付定金");
            }

            PaymentProvider provider = activeProvider();
            PaymentTransaction tx = Optional.ofNullable(latestDeposit(order.getId(), PaymentTransaction.PENDING))
                    .filter(t -> t.getAmount() == amount && provider.id().equals(t.getProvider()))
                    .orElse(null);
            if (tx == null) {
                tx = new PaymentTransaction();
                tx.setId(newTransactionId());
                tx.setOrderId(order.getId());
                tx.setAmount(amount);
                tx.setProvider(provider.id());
                tx.setPaymentMethod(sanitizeMethod(paymentMethod));
                tx.setPayerName(payerName != null && payerName.length() <= 100 ? payerName : order.getFarmerName());
                tx.setStatus(PaymentTransaction.PENDING);
                tx.setCreatedAt(LocalDateTime.now());
                tx = txRepository.save(tx);
            }

            Map<String, Object> next;
            try {
                next = provider.buildCheckout(tx, order);
            } catch (IllegalStateException e) {
                throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("transactionId", tx.getId());
            result.put("orderId", order.getId());
            result.put("provider", provider.id());
            result.put("status", tx.getStatus());
            result.put("amount", tx.getAmount());
            result.put("currency", "TWD");
            result.put("paymentMethod", tx.getPaymentMethod());
            result.put("alreadyPaid", false);
            result.put("next", next);
            result.put("message", "已建立定金 NT$ " + String.format("%,d", amount) + " 元待付款交易");
            return result;
        }
    }

    /**
     * mock 金流確認付款（僅 demo 模式；非 demo 回 404）
     */
    public Map<String, Object> mockConfirm(AuthContext ctx, String txId) {
        if (!settings.isDemoEnabled()) {
            throw ApiException.notFound("NOT_FOUND");
        }
        synchronized (lock) {
            PaymentTransaction tx = txRepository.findById(txId)
                    .orElseThrow(() -> ApiException.notFound("查無此交易序號：" + txId));
            if (!"mock".equals(tx.getProvider())) {
                throw ApiException.badRequest("此交易不是模擬金流交易");
            }
            Order order = orderService.requireOrder(tx.getOrderId());
            orderService.checkOwnerOrAdmin(ctx, order);
            if (PaymentTransaction.PAID.equals(tx.getStatus())) {
                return paidResponse(order, tx, true);
            }
            if (order.isDepositPaid()) {
                throw ApiException.conflict("此訂單已由其他交易完成付款");
            }
            if (OrderService.CANCELLED.equals(order.getStatus())) {
                throw ApiException.conflict("訂單已取消，無法付款");
            }
            if (tx.getAmount() != order.getDepositAmount()) {
                throw ApiException.conflict("訂單定金已變更，請重新發起付款");
            }
            tx.setAuthCode("MOCK" + digits(6));
            tx.setInvoiceNo("AG-MOCK-" + digits(6));
            markPaid(tx, order, null);
            return paidResponse(orderService.requireOrder(order.getId()), tx, false);
        }
    }

    /**
     * 綠界付款結果通知（伺服器對伺服器）：驗證 CheckMacValue 後才標記已付款
     * @return 綠界要求的回應字串：成功 "1|OK"
     */
    public String handleEcpayNotify(Map<String, String> params) {
        if (!ecpay.verifyNotify(params)) {
            log.warn("[ECPay] CheckMacValue 驗證失敗，拒絕通知 MerchantTradeNo={}", params.get("MerchantTradeNo"));
            throw ApiException.badRequest("0|CheckMacValue Error");
        }
        synchronized (lock) {
            String tradeNo = params.get("MerchantTradeNo");
            PaymentTransaction tx = tradeNo == null ? null : txRepository.findById(tradeNo).orElse(null);
            if (tx == null || !"ecpay".equals(tx.getProvider())) {
                throw ApiException.badRequest("0|Order Not Found");
            }
            if (PaymentTransaction.PAID.equals(tx.getStatus())) {
                return "1|OK"; // 重複通知：冪等
            }
            if (!"1".equals(params.get("RtnCode"))) {
                tx.setStatus(PaymentTransaction.FAILED);
                txRepository.save(tx);
                return "1|OK";
            }
            if (!String.valueOf(tx.getAmount()).equals(params.get("TradeAmt"))) {
                log.warn("[ECPay] 金額不符 tx={} expected={} got={}", tx.getId(), tx.getAmount(), params.get("TradeAmt"));
                throw ApiException.badRequest("0|Amount Mismatch");
            }
            Order order = orderService.requireOrder(tx.getOrderId());
            // 款項已入帳（交易記為已付），但下列情況不可把訂單標成已付定金，需人工退款或補收：
            // 已由其他交易付款、訂單已取消，或付款後規格變更使定金與這筆交易金額不同（舊交易頁付了較少的定金）
            String manual = order.isDepositPaid() ? "已由其他交易付款"
                    : OrderService.CANCELLED.equals(order.getStatus()) ? "訂單已取消"
                    : tx.getAmount() != order.getDepositAmount() ? "定金已變更為 " + order.getDepositAmount() + " 元"
                    : null;
            if (manual != null) {
                tx.setStatus(PaymentTransaction.PAID);
                tx.setGatewayTradeNo(params.get("TradeNo"));
                tx.setPaidAt(LocalDateTime.now());
                txRepository.save(tx);
                log.warn("[ECPay] 訂單 {} {}，交易 {}（{} 元）需人工退款或補收處理", order.getId(), manual, tx.getId(), tx.getAmount());
                return "1|OK";
            }
            markPaid(tx, order, params.get("TradeNo"));
            return "1|OK";
        }
    }

    private void markPaid(PaymentTransaction tx, Order order, String gatewayTradeNo) {
        tx.setStatus(PaymentTransaction.PAID);
        tx.setPaidAt(LocalDateTime.now());
        if (gatewayTradeNo != null) tx.setGatewayTradeNo(gatewayTradeNo);
        txRepository.save(tx);
        orderService.markDepositPaid(order);
    }

    private Map<String, Object> paidResponse(Order order, PaymentTransaction tx, boolean alreadyPaid) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("transactionId", tx != null ? tx.getId() : null);
        result.put("orderId", order.getId());
        result.put("farmerName", order.getFarmerName());
        result.put("provider", tx != null ? tx.getProvider() : null);
        result.put("paymentMethod", tx != null ? tx.getPaymentMethod() : null);
        result.put("amountPaid", tx != null ? tx.getAmount() : order.getDepositAmount());
        result.put("currency", "TWD");
        result.put("status", "SUCCESS");
        result.put("authCode", tx != null ? tx.getAuthCode() : null);
        result.put("invoiceNo", tx != null ? tx.getInvoiceNo() : null);
        result.put("paymentTime", tx != null && tx.getPaidAt() != null ? tx.getPaidAt().format(TS) : null);
        result.put("orderStatus", order.getStatus());
        result.put("alreadyPaid", alreadyPaid);
        result.put("message", alreadyPaid ? "此訂單定金已付清，無需重複付款"
                : "定金 NT$ " + String.format("%,d", result.get("amountPaid")) + " 元已成功支付！訂單已轉為【已排定】。");
        return result;
    }

    /** 交易序號：AG + yyMMddHHmmss + 6 碼 SecureRandom 英數（共 20 碼，符合綠界 MerchantTradeNo 限制） */
    private String newTransactionId() {
        String id;
        do {
            StringBuilder sb = new StringBuilder("AG").append(LocalDateTime.now().format(TX_TS));
            for (int i = 0; i < 6; i++) sb.append(ALNUM[random.nextInt(ALNUM.length)]);
            id = sb.toString();
        } while (txRepository.existsById(id));
        return id;
    }

    private String digits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }

    private static String sanitizeMethod(String method) {
        if (method == null || method.isBlank()) return "credit_card";
        return Set.of("credit_card", "line_pay", "atm_virtual").contains(method) ? method : "credit_card";
    }

    /**
     * 取得目前訂單之金流狀態
     */
    public Map<String, Object> getPaymentStatus(AuthContext ctx, String orderId) {
        Order order = orderService.requireOrder(orderId);
        orderService.checkOwnerOrAdmin(ctx, order);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("orderId", order.getId());
        res.put("farmerName", order.getFarmerName());
        res.put("finalAmount", order.getFinalAmount());
        res.put("depositAmount", order.getDepositAmount());
        res.put("depositRequired", order.getDepositAmount() > 0);
        res.put("depositPaid", order.isDepositPaid());
        res.put("orderStatus", order.getStatus());
        List<PaymentTransaction> txs = txRepository.findByOrderIdOrderByCreatedAtDesc(order.getId());
        res.putAll(balanceOf(order, txs));
        res.put("transactions", txs);
        return res;
    }

    // ------------------------------------------------------------------ 尾款收款登錄（admin）

    private static final Set<String> BALANCE_METHODS = Set.of("bank_transfer", "cash", "check", "other");
    private static final java.util.regex.Pattern INVOICE_NO = java.util.regex.Pattern.compile("^([A-Z]{2})-?(\\d{8})$");

    /** 最新一筆指定狀態的定金交易（排除尾款登錄） */
    private PaymentTransaction latestDeposit(String orderId, String status) {
        return txRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(t -> !t.isBalance() && status.equals(t.getStatus()))
                .findFirst().orElse(null);
    }

    /**
     * 尾款結算：已付定金以訂單 depositPaid 為準（線上金流已核對金額）；尾款 = 總額 − 已付定金 − 已登錄尾款（作廢不計）
     */
    private Map<String, Object> balanceOf(Order order, List<PaymentTransaction> txs) {
        int depositCredit = order.isDepositPaid() ? Math.max(0, order.getDepositAmount()) : 0;
        int balancePaid = txs.stream().filter(t -> t.isBalance() && PaymentTransaction.PAID.equals(t.getStatus()))
                .mapToInt(PaymentTransaction::getAmount).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("depositCredit", depositCredit);
        m.put("balancePaid", balancePaid);
        m.put("balanceOutstanding", Math.max(0, order.getFinalAmount() - depositCredit - balancePaid));
        return m;
    }

    /**
     * 登錄尾款收款（合作社另行收款後由 admin 登錄）：訂單須已完工；金額預設為未結清尾款，可分次登錄但不得超過未結清金額；
     * 收款日不可晚於今天；發票號碼可稍後補登
     */
    public Map<String, Object> recordBalance(String orderId, Integer amount, String paidDate, String method,
                                             String invoiceNo, String note) {
        synchronized (lock) {
            Order order = orderService.requireOrder(orderId);
            if (!OrderService.COMPLETED.equals(order.getStatus())) {
                throw ApiException.conflict("訂單完工後才能登錄尾款（目前狀態：" + order.getStatus() + "）");
            }
            int outstanding = (int) balanceOf(order, txRepository.findByOrderIdOrderByCreatedAtDesc(orderId)).get("balanceOutstanding");
            if (outstanding <= 0) throw ApiException.conflict("此訂單尾款已結清");
            int value = amount == null ? outstanding : amount;
            if (value <= 0 || value > outstanding) {
                throw ApiException.badRequest("收款金額須介於 1 ～ " + outstanding + " 元（未結清尾款）");
            }
            java.time.LocalDate day;
            try {
                day = paidDate == null || paidDate.isBlank() ? java.time.LocalDate.now() : java.time.LocalDate.parse(paidDate.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.badRequest("收款日期 (paidDate) 格式須為 yyyy-MM-dd");
            }
            if (day.isAfter(java.time.LocalDate.now())) throw ApiException.badRequest("收款日期不可晚於今天");
            String m = method == null || method.isBlank() ? "bank_transfer" : method.trim();
            if (!BALANCE_METHODS.contains(m)) throw ApiException.badRequest("收款方式須為 bank_transfer / cash / check / other");

            PaymentTransaction tx = new PaymentTransaction();
            tx.setId(newTransactionId());
            tx.setOrderId(order.getId());
            tx.setKind(PaymentTransaction.KIND_BALANCE);
            tx.setProvider(PaymentTransaction.PROVIDER_OFFLINE);
            tx.setPaymentMethod(m);
            tx.setAmount(value);
            tx.setPayerName(order.getFarmerName());
            tx.setStatus(PaymentTransaction.PAID);
            tx.setInvoiceNo(invoiceNo == null || invoiceNo.isBlank() ? null : normalizeInvoiceNo(invoiceNo));
            tx.setNote(checkNote(note));
            tx.setCreatedAt(LocalDateTime.now());
            tx.setPaidAt(day.atStartOfDay());
            txRepository.save(tx);
            log.info("[尾款] 訂單 {} 登錄尾款 {} 元（{}，交易 {}）", order.getId(), value, m, tx.getId());
            return balanceResult(order, tx);
        }
    }

    /** 補登／更正發票號碼（admin）：限已付款交易；格式為 2 碼英文＋8 碼數字 */
    public Map<String, Object> setInvoiceNo(String txId, String invoiceNo) {
        synchronized (lock) {
            PaymentTransaction tx = txRepository.findById(txId)
                    .orElseThrow(() -> ApiException.notFound("查無此交易序號：" + txId));
            if (!PaymentTransaction.PAID.equals(tx.getStatus())) throw ApiException.conflict("只有已付款的交易可以登錄發票號碼");
            if (invoiceNo == null || invoiceNo.isBlank()) throw ApiException.badRequest("請填寫發票號碼");
            tx.setInvoiceNo(normalizeInvoiceNo(invoiceNo));
            txRepository.save(tx);
            return balanceResult(orderService.requireOrder(tx.getOrderId()), tx);
        }
    }

    /** 作廢尾款登錄（admin，登錄錯誤時用；保留紀錄、不計入已收）：線上金流的定金交易不可作廢 */
    public Map<String, Object> voidBalance(String txId, String reason) {
        synchronized (lock) {
            PaymentTransaction tx = txRepository.findById(txId)
                    .orElseThrow(() -> ApiException.notFound("查無此交易序號：" + txId));
            if (!tx.isBalance()) throw ApiException.conflict("只有尾款登錄紀錄可以作廢；定金退款請洽金流商處理");
            if (!PaymentTransaction.PAID.equals(tx.getStatus())) throw ApiException.conflict("此紀錄已作廢");
            if (reason == null || reason.isBlank()) throw ApiException.badRequest("請填寫作廢原因");
            String why = "作廢：" + reason.trim();
            tx.setNote(checkNote(tx.getNote() == null || tx.getNote().isBlank() ? why : tx.getNote() + "／" + why));
            tx.setStatus(PaymentTransaction.VOID);
            txRepository.save(tx);
            log.info("[尾款] 交易 {}（訂單 {}）已作廢：{}", tx.getId(), tx.getOrderId(), reason.trim());
            return balanceResult(orderService.requireOrder(tx.getOrderId()), tx);
        }
    }

    private Map<String, Object> balanceResult(Order order, PaymentTransaction tx) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("orderId", order.getId());
        res.put("finalAmount", order.getFinalAmount());
        res.putAll(balanceOf(order, txRepository.findByOrderIdOrderByCreatedAtDesc(order.getId())));
        res.put("transaction", tx);
        return res;
    }

    static String normalizeInvoiceNo(String raw) {
        java.util.regex.Matcher m = INVOICE_NO.matcher(raw.trim().toUpperCase(Locale.ROOT));
        if (!m.matches()) throw ApiException.badRequest("發票號碼格式須為 2 碼英文＋8 碼數字（例：AB-12345678）");
        return m.group(1) + "-" + m.group(2);
    }

    private static String checkNote(String note) {
        if (note == null || note.isBlank()) return null;
        String n = note.trim();
        if (n.length() > 255) throw ApiException.badRequest("備註過長（上限 255 字）");
        return n;
    }

    /**
     * 支援的金流管道列表
     */
    public List<Map<String, Object>> getSupportedMethods() {
        List<Map<String, Object>> list = new ArrayList<>();

        Map<String, Object> m1 = new HashMap<>();
        m1.put("id", "credit_card");
        m1.put("name", "綠界 ECPay 信用卡 / 3D 快速刷卡");
        m1.put("icon", "💳");
        m1.put("fee", "手續費依金流合約【待定】");
        m1.put("desc", "經綠界 ECPay 付款頁刷卡（支援 Visa / MasterCard / JCB）");
        list.add(m1);

        Map<String, Object> m2 = new HashMap<>();
        m2.put("id", "line_pay");
        m2.put("name", "LINE Pay 行動支付（尚未串接）");
        m2.put("icon", "💚");
        m2.put("fee", "手續費依金流合約【待定】");
        m2.put("desc", "需另行申請 LINE Pay 特約商店並串接 API；目前僅展示模式模擬");
        list.add(m2);

        Map<String, Object> m3 = new HashMap<>();
        m3.put("id", "atm_virtual");
        m3.put("name", "ATM 虛擬帳號 / 跨行轉帳");
        m3.put("icon", "🏧");
        m3.put("fee", "依各銀行跨行費率");
        m3.put("desc", "經綠界 ECPay 產生虛擬帳號，入帳後由綠界通知自動核銷");
        list.add(m3);

        return list;
    }
}
