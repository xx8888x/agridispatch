package com.agridispatch.security;

import com.agridispatch.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自助註冊次數限制（規格 03 第 5 節，記憶體實作，單一後端實例）：
 * 同一來源 IP 在時間窗內最多送出 N 次註冊請求（預設 1 小時 10 次），超過回 429。
 * 不論成功失敗都計數，避免以註冊端點大量建帳號或探測手機號碼是否已註冊。
 */
@Component
public class RegisterRateLimiter {

    public static final String LOCKED_MESSAGE = "註冊次數過多，請稍後再試";

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int maxPerWindow;
    private final long windowMillis;
    private final Map<String, Deque<Long>> ips = new ConcurrentHashMap<>();

    public RegisterRateLimiter(@Value("${agri.security.register-ip-max:10}") int maxPerWindow,
                               @Value("${agri.security.register-window-minutes:60}") long windowMinutes) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
        this.windowMillis = Math.max(1, windowMinutes) * 60_000L;
    }

    /** 測試用：清除全部紀錄 */
    public void reset() {
        ips.clear();
    }

    /** 記一次註冊請求；超過上限 → 429 */
    public void acquire(String ip) {
        long now = System.currentTimeMillis();
        String key = ip == null ? "" : ip.trim();
        Deque<Long> q = ips.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= now - windowMillis) q.pollFirst();
            if (q.size() >= maxPerWindow) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, LOCKED_MESSAGE);
            }
            q.addLast(now);
        }
        if (ips.size() > CLEANUP_THRESHOLD) {
            ips.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    Long last = e.getValue().peekLast();
                    return last == null || last <= now - windowMillis;
                }
            });
        }
    }
}
