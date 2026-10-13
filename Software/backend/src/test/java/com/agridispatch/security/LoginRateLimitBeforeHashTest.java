package com.agridispatch.security;

import com.agridispatch.common.ApiException;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.repository.FarmerRepository;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.service.AuthService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 鎖定中的帳號 / IP 必須在執行 PBKDF2（與查資料庫）之前就被擋下，避免以洪泛請求耗用 CPU。
 * （本機 JDK 26 不支援 Mockito 的 Byte Buddy，改用手寫替身）
 */
class LoginRateLimitBeforeHashTest {

    /** 記錄呼叫次數的 PasswordHasher 替身（不跑 PBKDF2） */
    private static final class CountingHasher extends PasswordHasher {
        final AtomicInteger calls = new AtomicInteger();
        @Override public String hash(String raw) { calls.incrementAndGet(); return "x"; }
        @Override public boolean matches(String raw, String stored) { calls.incrementAndGet(); return false; }
        @Override public void burnTime(String raw) { calls.incrementAndGet(); }
    }

    /** 記錄所有呼叫的 repository 替身：查詢一律回空 */
    @SuppressWarnings("unchecked")
    private static <T> T recordingRepo(Class<T> type, List<String> log) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> type.getSimpleName();
                };
            }
            log.add(type.getSimpleName() + "." + m.getName());
            if (m.getReturnType() == Optional.class) return Optional.empty();
            if (m.getReturnType() == List.class) return List.of();
            return null;
        });
    }

    private static AgriSettings settings() {
        AgriSettings s = new AgriSettings();
        org.springframework.test.util.ReflectionTestUtils.setField(s, "tokenSecret", "unit-test-secret-0123456789abcdef-xyz");
        return s;
    }

    @Test
    void lockedLoginNeverTouchesPasswordHasherOrRepositories() {
        List<String> repoCalls = new ArrayList<>();
        CountingHasher hasher = new CountingHasher();
        LoginRateLimiter limiter = new LoginRateLimiter(5, 30, 15, 15);
        AuthService auth = new AuthService(recordingRepo(UserRepository.class, repoCalls),
                recordingRepo(WorkerRepository.class, repoCalls), hasher, new TokenService(settings()), settings(), limiter,
                recordingRepo(FarmerRepository.class, repoCalls), new RegisterRateLimiter(10, 60),
                recordingRepo(com.agridispatch.repository.PushTokenRepository.class, repoCalls));

        for (int i = 0; i < 5; i++) limiter.recordFailure("farmer", "10.1.1.1");

        ApiException e = assertThrows(ApiException.class, () -> auth.login("farmer", "whatever", "10.1.1.1"));
        assertEquals(429, e.getStatus().value());
        assertEquals(0, hasher.calls.get(), "鎖定時不可執行 PBKDF2");
        assertTrue(repoCalls.isEmpty(), "鎖定時不可查資料庫：" + repoCalls);

        // IP 鎖定同樣在 PBKDF2 之前擋下
        for (int i = 0; i < 30; i++) limiter.recordFailure("other" + i, "10.2.2.2");
        assertThrows(ApiException.class, () -> auth.login("admin_tech", "pw", "10.2.2.2"));
        assertEquals(0, hasher.calls.get());
    }

    @Test
    void failedLoginIsCountedTowardLock() {
        List<String> repoCalls = new ArrayList<>();
        CountingHasher hasher = new CountingHasher();
        LoginRateLimiter limiter = new LoginRateLimiter(5, 30, 15, 15);
        AuthService auth = new AuthService(recordingRepo(UserRepository.class, repoCalls),
                recordingRepo(WorkerRepository.class, repoCalls), hasher, new TokenService(settings()), settings(), limiter,
                recordingRepo(FarmerRepository.class, repoCalls), new RegisterRateLimiter(10, 60),
                recordingRepo(com.agridispatch.repository.PushTokenRepository.class, repoCalls));

        for (int i = 0; i < 5; i++) assertTrue(auth.login("nobody", "pw", "10.1.1.3").isEmpty());
        assertEquals(5, hasher.calls.get(), "查無帳號時仍以 burnTime 消耗等量時間");
        assertThrows(ApiException.class, () -> auth.login("nobody", "pw", "10.1.1.3"));
        assertEquals(5, hasher.calls.get());
    }
}
