package com.agridispatch.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 金流交易紀錄：定金（線上金流）與尾款（合作社另行收款後由 admin 登錄，kind = balance、provider = offline）
 * status：PENDING（待付款）→ PAID（已付款）/ FAILED（失敗）
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    public static final String FAILED = "FAILED";
    /** 作廢（僅尾款登錄紀錄；登錄錯誤時由 admin 作廢，不刪除） */
    public static final String VOID = "VOID";

    /** 款項類別：deposit 定金（線上金流）／balance 尾款（合作社另行收款後由 admin 登錄） */
    public static final String KIND_DEPOSIT = "deposit";
    public static final String KIND_BALANCE = "balance";
    /** 尾款登錄的 provider（非線上金流） */
    public static final String PROVIDER_OFFLINE = "offline";

    /** 交易序號（亦作為綠界 MerchantTradeNo，英數 20 碼內） */
    @Id
    @Column(length = 20)
    private String id;

    @Column(name = "order_id", nullable = false, length = 40)
    private String orderId;

    /** 金額（新台幣元，後端依訂單計算） */
    @Column(nullable = false)
    private int amount;

    /** mock | ecpay */
    @Column(length = 16, nullable = false)
    private String provider;

    /** credit_card / line_pay / atm_virtual / ALL ... */
    @Column(length = 32)
    private String paymentMethod;

    @Column(length = 16, nullable = false)
    private String status;

    private String payerName;

    /** 金流商交易編號（綠界 TradeNo） */
    @Column(length = 40)
    private String gatewayTradeNo;

    @Column(length = 20)
    private String authCode;

    @Column(length = 20)
    private String invoiceNo;

    /** deposit／balance；舊資料 NULL 視同 deposit */
    @Column(length = 16)
    private String kind;

    /** 備註（尾款登錄說明、作廢原因） */
    @Column(length = 255)
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime paidAt;

    public PaymentTransaction() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPayerName() { return payerName; }
    public void setPayerName(String payerName) { this.payerName = payerName; }

    public String getGatewayTradeNo() { return gatewayTradeNo; }
    public void setGatewayTradeNo(String gatewayTradeNo) { this.gatewayTradeNo = gatewayTradeNo; }

    public String getAuthCode() { return authCode; }
    public void setAuthCode(String authCode) { this.authCode = authCode; }

    public String getInvoiceNo() { return invoiceNo; }
    public void setInvoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; }

    public String getKind() { return kind == null ? KIND_DEPOSIT : kind; }
    public void setKind(String kind) { this.kind = kind; }
    @JsonIgnore
    public boolean isBalance() { return KIND_BALANCE.equals(kind); }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
}
