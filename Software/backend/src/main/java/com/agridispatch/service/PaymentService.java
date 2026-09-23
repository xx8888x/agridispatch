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
                return paidResponse(order, txRepository.findFirstByOrderIdAndStatusOrderByCreatedAtDesc(order.getId(),
                        PaymentTransaction.PAID).orElse(null), true);
            }
            if (OrderService.COMPLETED.equals(order.getStatus())) {
                throw ApiException.conflict("訂單已完工，無需再支付定金");
            }
            int amount = order.getDepositAmount();
            if (amount <= 0) {
                throw ApiException.badRequest("此方案無需預付定金");
            }

            PaymentProvider provider = activeProvider();
            PaymentTransaction tx = txRepository
                    .findFirstByOrderIdAndStatusOrderByCreatedAtDesc(order.getId(), PaymentTransaction.PENDING)
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
            if (order.isDepositPaid()) {
                tx.setStatus(PaymentTransaction.PAID);
                tx.setGatewayTradeNo(params.get("TradeNo"));
                tx.setPaidAt(LocalDateTime.now());
                txRepository.save(tx);
                log.warn("[ECPay] 訂單 {} 已由其他交易付款，交易 {} 需人工退款處理", order.getId(), tx.getId());
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
        res.put("transactions", txRepository.findByOrderIdOrderByCreatedAtDesc(order.getId()));
        return res;
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
        m1.put("fee", "0 元 (平台吸收手續費)");
        m1.put("desc", "支援 Visa, MasterCard, JCB，全程 SSL 256-bit 加密安全認證");
        list.add(m1);

        Map<String, Object> m2 = new HashMap<>();
        m2.put("id", "line_pay");
        m2.put("name", "LINE Pay 行動支付");
        m2.put("icon", "💚");
        m2.put("fee", "0 元 (享 LINE POINTS 回饋)");
        m2.put("desc", "雙擊即掃碼結帳，綁定手機免輸入卡號");
        list.add(m2);

        Map<String, Object> m3 = new HashMap<>();
        m3.put("id", "atm_virtual");
        m3.put("name", "ATM 虛擬帳號 / 跨行轉帳");
        m3.put("icon", "🏧");
        m3.put("fee", "依各銀行跨行費率");
        m3.put("desc", "自動生成個人專屬 16 碼虛擬帳號，轉帳完成 3 秒內自動核銷");
        list.add(m3);

        return list;
    }
}
