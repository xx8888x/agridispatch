package com.agridispatch.security;

import com.agridispatch.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登入失敗次數限制（規格 03 第 5 節，記憶體實作，單一後端實例）
 * - 同一帳號識別碼在時間窗內失敗達上限 → 鎖定一段時間（預設 15 分鐘 5 次、鎖 15 分鐘）
 * - 同一來源 IP 在時間窗內失敗達上限 → 同樣鎖定（預設 15 分鐘 30 次）
 * - 必須在驗證密碼（PBKDF2）之前呼叫 {@link #checkAllowed}，避免被洪泛耗用 CPU
 * - 登入成功清除該帳號的失敗紀錄（IP 紀錄保留，避免以自有帳號洗掉 IP 計數）
 */
@Component
public class LoginRateLimiter {

    public static final String LOCKED_MESSAGE = "嘗試次數過多，請 15 分鐘後再試";

    /** 超過此數量才進行過期清理，避免每次請求都掃描 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int maxAccountFailures;
    private final int maxIpFailures;
    private final long windowMillis;
    private final long lockMillis;
    private volatile Clock clock = Clock.systemUTC();

    private final Map<String, Entry> accounts = new ConcurrentHashMap<>();
    private final Map<String, Entry> ips = new ConcurrentHashMap<>();

    public LoginRateLimiter(@Value("${agri.security.login-max-failures:5}") int maxAccountFailures,
                            @Value("${agri.security.login-ip-max-failures:30}") int maxIpFailures,
                            @Value("${agri.security.login-window-minutes:15}") long windowMinutes,
                            @Value("${agri.security.login-lock-minutes:15}") long lockMinutes) {
        this.maxAccountFailures = Math.max(1, maxAccountFailures);
        this.maxIpFailures = Math.max(1, maxIpFailures);
        this.windowMillis = Math.max(1, windowMinutes) * 60_000L;
        this.lockMillis = Math.max(1, lockMinutes) * 60_000L;
    }

    /** 測試用：替換時鐘 */
    void setClock(Clock clock) { this.clock = clock; }

    /** 測試用：清除全部紀錄 */
    public void reset() {
        accounts.clear();
        ips.clear();
    }

    /** 帳號或 IP 鎖定中 → 429 */
    public void checkAllowed(String identifier, String ip) {
        long now = clock.millis();
        if (isLocked(accounts, accountKey(identifier), now) || isLocked(ips, ipKey(ip), now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, LOCKED_MESSAGE);
        }
    }

    public void recordFailure(String identifier, String ip) {
        long now = clock.millis();
        record(accounts, accountKey(identifier), maxAccountFailures, now);
        record(ips, ipKey(ip), maxIpFailures, now);
        if (accounts.size() + ips.size() > CLEANUP_THRESHOLD) cleanup(now);
    }

    public void recordSuccess(String identifier) {
        accounts.remove(accountKey(identifier));
    }

    // ------------------------------------------------------------------

    private static String accountKey(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String ip) {
        return ip == null ? "" : ip.trim();
    }

    private boolean isLocked(Map<String, Entry> map, String key, long now) {
        Entry e = map.get(key);
        if (e == null) return false;
        synchronized (e) {
            return e.lockedUntil > now;
        }
    }

    private void record(Map<String, Entry> map, String key, int max, long now) {
        Entry e = map.computeIfAbsent(key, k -> new Entry());
        synchronized (e) {
            e.prune(now - windowMillis);
            e.failures.addLast(now);
            if (e.failures.size() >= max) {
                e.lockedUntil = now + lockMillis;
                e.failures.clear();
            }
        }
    }

    private void cleanup(long now) {
        for (Map<String, Entry> map : java.util.List.of(accounts, ips)) {
            map.entrySet().removeIf(en -> {
                Entry e = en.getValue();
                synchronized (e) {
                    e.prune(now - windowMillis);
                    return e.failures.isEmpty() && e.lockedUntil <= now;
                }
            });
        }
    }

    private static final class Entry {
        final Deque<Long> failures = new ArrayDeque<>();
        long lockedUntil;

        void prune(long cutoff) {
            while (!failures.isEmpty() && failures.peekFirst() <= cutoff) failures.removeFirst();
        }
    }
}
