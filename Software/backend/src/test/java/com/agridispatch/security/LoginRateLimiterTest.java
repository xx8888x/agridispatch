package com.agridispatch.security;

import com.agridispatch.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 登入失敗次數限制（記憶體實作）的單元測試：門檻、時間窗、鎖定到期、成功清除、IP 門檻
 */
class LoginRateLimiterTest {

    /** 可手動前進的時鐘 */
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    private LoginRateLimiter limiter() {
        LoginRateLimiter l = new LoginRateLimiter(5, 30, 15, 15);
        l.setClock(clock);
        return l;
    }

    private static void assertLocked(LoginRateLimiter l, String id, String ip) {
        ApiException e = assertThrows(ApiException.class, () -> l.checkAllowed(id, ip));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        assertEquals("嘗試次數過多，請 15 分鐘後再試", e.getMessage());
    }

    @Test
    void accountLocksOnFifthFailureAndUnlocksAfter15Minutes() {
        LoginRateLimiter l = limiter();
        for (int i = 0; i < 4; i++) {
            l.checkAllowed("farmer", "1.1.1.1");
            l.recordFailure("farmer", "1.1.1.1");
        }
        l.checkAllowed("farmer", "1.1.1.1");          // 4 次失敗仍可嘗試
        l.recordFailure("farmer", "1.1.1.1");          // 第 5 次 → 鎖定
        assertLocked(l, "farmer", "1.1.1.1");
        assertLocked(l, " FARMER ", "2.2.2.2");        // 帳號鎖定與來源 IP 無關，識別碼不分大小寫
        l.checkAllowed("farmer_yilan", "1.1.1.1");     // 其他帳號不受影響

        clock.advance(Duration.ofMinutes(14));
        assertLocked(l, "farmer", "1.1.1.1");
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        l.checkAllowed("farmer", "1.1.1.1");            // 15 分鐘後解鎖
    }

    @Test
    void failuresOutsideWindowDoNotCount() {
        LoginRateLimiter l = limiter();
        for (int i = 0; i < 4; i++) l.recordFailure("W001", "1.1.1.1");
        clock.advance(Duration.ofMinutes(16));
        for (int i = 0; i < 4; i++) l.recordFailure("W001", "1.1.1.1");
        l.checkAllowed("W001", "1.1.1.1");
    }

    @Test
    void successClearsAccountCounter() {
        LoginRateLimiter l = limiter();
        for (int i = 0; i < 4; i++) l.recordFailure("admin_tech", "1.1.1.1");
        l.recordSuccess("admin_tech");
        for (int i = 0; i < 4; i++) l.recordFailure("admin_tech", "1.1.1.1");
        l.checkAllowed("admin_tech", "1.1.1.1");
    }

    @Test
    void ipLocksAfterThirtyFailuresAcrossAccounts() {
        LoginRateLimiter l = limiter();
        for (int i = 0; i < 29; i++) l.recordFailure("user" + i, "9.9.9.9");
        l.checkAllowed("someone", "9.9.9.9");
        l.recordFailure("user29", "9.9.9.9");
        assertLocked(l, "someone", "9.9.9.9");          // 任何帳號都不能從此 IP 登入
        l.checkAllowed("someone", "8.8.8.8");            // 其他 IP 不受影響
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        l.checkAllowed("someone", "9.9.9.9");
    }

    @Test
    void thresholdsAreConfigurable() {
        LoginRateLimiter l = new LoginRateLimiter(2, 100, 15, 15);
        l.setClock(clock);
        l.recordFailure("x", "1.1.1.1");
        l.checkAllowed("x", "1.1.1.1");
        l.recordFailure("x", "1.1.1.1");
        assertLocked(l, "x", "1.1.1.1");
    }
}
