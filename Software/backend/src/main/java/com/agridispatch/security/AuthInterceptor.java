package com.agridispatch.security;

import com.agridispatch.config.AgriSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API 認證與授權攔截器（規格第 5 節）
 * - 公開清單外的 /api/** 一律需要 Bearer token
 * - 管理員專屬路徑、本人或管理員路徑於此統一檢查
 * - 訂單擁有者（farmer）等資源層級檢查於 Controller/Service 內進行
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    static {
        PATH_HELPER.setRemoveSemicolonContent(true);
        PATH_HELPER.setUrlDecode(true);
    }

    /** 公開端點：{method, pattern} */
    private static final List<String[]> PUBLIC = List.of(
            new String[]{"POST", "/api/auth/login"},
            new String[]{"POST", "/api/auth/register"},       // 農場主自助註冊（RegisterRateLimiter 限次）
            new String[]{"POST", "/api/auth/demo-login"},
            new String[]{"GET", "/api/auth/quick-profiles"},   // 僅 demo 模式有效，非 demo 回 404
            new String[]{"GET", "/api/public/**"},
            new String[]{"GET", "/api/crops"},
            new String[]{"GET", "/api/crops/**"},
            new String[]{"GET", "/api/calendar"},
            new String[]{"GET", "/api/calendar/**"},
            new String[]{"GET", "/api/brochures"},
            new String[]{"GET", "/api/brochures/**"},
            new String[]{"GET", "/api/payments/methods"},
            new String[]{"GET", "/api/orders/categories"},
            new String[]{"POST", "/api/orders/calculate"},
            new String[]{"POST", "/api/talents/register"},
            new String[]{"GET", "/api/dispatch/overview"},
            new String[]{"GET", "/api/dispatch/weather-state"},
            new String[]{"GET", "/api/legalize/types"},          // 黑轉白身分類型與法規參考文字
            new String[]{"POST", "/api/payments/ecpay/notify"},  // 以 CheckMacValue 驗證
            new String[]{"POST", "/api/line/webhook"},            // 已停用：一律回 410 Gone（不處理訊息）
            new String[]{"POST", "/api/line/internal/**"}         // LINE Bot 內部端點：以 X-Internal-Key 驗證（LineController）
    );

    /** 僅 admin_tech：{method("*" = 任意), pattern} */
    private static final List<String[]> ADMIN_ONLY = List.of(
            new String[]{"POST", "/api/crops/**"}, new String[]{"POST", "/api/crops"},
            new String[]{"PUT", "/api/crops/**"}, new String[]{"DELETE", "/api/crops/**"},
            new String[]{"POST", "/api/brochures"}, new String[]{"POST", "/api/brochures/**"},
            new String[]{"PUT", "/api/brochures/**"}, new String[]{"DELETE", "/api/brochures/**"},
            new String[]{"GET", "/api/talents"}, new String[]{"GET", "/api/talents/**"},
            new String[]{"POST", "/api/talents"},
            new String[]{"PUT", "/api/talents/**"}, new String[]{"DELETE", "/api/talents/**"},
            new String[]{"*", "/api/payroll/**"},                // 出勤補登／修正、月結算（PayrollController）
            new String[]{"GET", "/api/workers"},
            new String[]{"POST", "/api/workers"},
            new String[]{"PUT", "/api/workers/*"}, new String[]{"DELETE", "/api/workers/*"},
            new String[]{"POST", "/api/dispatch/**"}, new String[]{"PUT", "/api/dispatch/**"},
            new String[]{"DELETE", "/api/dispatch/**"},
            new String[]{"*", "/api/line/config"},
            new String[]{"GET", "/api/line/bindings"}, new String[]{"DELETE", "/api/line/bindings/**"},
            new String[]{"*", "/api/line/notify/**"},
            new String[]{"POST", "/api/notifications/send"},
            new String[]{"PUT", "/api/orders/*/status"},
            new String[]{"POST", "/api/payments/*/balance"},             // 尾款收款登錄
            new String[]{"PUT", "/api/payments/transactions/*/invoice"}, // 補登發票號碼
            new String[]{"POST", "/api/payments/transactions/*/void"},   // 作廢尾款登錄
            new String[]{"GET", "/api/legalize/summary"},        // 各農場黑轉白彙總（科技後台）
            new String[]{"POST", "/api/auth/reset-password"},
            new String[]{"GET", "/api/auth/accounts"},
            new String[]{"GET", "/api/leaves"},                                    // 全部請假（合作社審核）
            new String[]{"POST", "/api/leaves/*/approve"}, new String[]{"POST", "/api/leaves/*/reject"},
            new String[]{"*", "/api/dev/**"}
    );

    private final TokenService tokenService;
    private final TokenVersionChecker tokenVersionChecker;
    private final AgriSettings settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthInterceptor(TokenService tokenService, TokenVersionChecker tokenVersionChecker, AgriSettings settings) {
        this.tokenService = tokenService;
        this.tokenVersionChecker = tokenVersionChecker;
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (CorsUtils.isPreFlightRequest(request)) return true;

        String method = request.getMethod().toUpperCase();
        String path = PATH_HELPER.getPathWithinApplication(request);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        // /api/dev/** 僅 demo 模式存在
        if (MATCHER.match("/api/dev/**", path) && !settings.isDemoEnabled()) {
            return reject(response, 404, "NOT_FOUND");
        }

        // 簽章與到期有效後，再比對帳號目前的 token 版本（改密碼 / 重設 / 刪除 / 變更角色後舊 token 失效）
        Optional<AuthContext> ctx = tokenService.verify(extractBearer(request))
                .filter(tokenVersionChecker::isCurrent);
        ctx.ifPresent(c -> request.setAttribute(AuthContext.REQUEST_ATTR, c));

        if (matchesAny(PUBLIC, method, path)) {
            return true;
        }
        if (ctx.isEmpty()) {
            return reject(response, 401, "UNAUTHORIZED");
        }
        AuthContext auth = ctx.get();

        if (matchesAny(ADMIN_ONLY, method, path) && !auth.isAdmin()) {
            return reject(response, 403, "FORBIDDEN");
        }

        // 本人或 admin：/api/workers/{id}/**
        String workerId = extractVar(path, "/api/workers/{id}", "/api/workers/{id}/**");
        if (workerId != null && !auth.isSelfOrAdmin(workerId)) {
            return reject(response, 403, "FORBIDDEN");
        }
        // 本人或 admin：/api/attendance/{kind}/{workerId}（summary 統計除外）
        if (MATCHER.match("/api/attendance/{kind}/{workerId}", path)) {
            Map<String, String> vars = MATCHER.extractUriTemplateVariables("/api/attendance/{kind}/{workerId}", path);
            if (!"summary".equals(vars.get("kind")) && !auth.isSelfOrAdmin(vars.get("workerId"))) {
                return reject(response, 403, "FORBIDDEN");
            }
        }
        return true;
    }

    private static String extractVar(String path, String exact, String nested) {
        for (String p : new String[]{exact, nested}) {
            if (MATCHER.match(p, path)) {
                return MATCHER.extractUriTemplateVariables(p, path).get("id");
            }
        }
        return null;
    }

    private static boolean matchesAny(List<String[]> rules, String method, String path) {
        for (String[] r : rules) {
            if (("*".equals(r[0]) || r[0].equals(method)) && MATCHER.match(r[1], path)) return true;
        }
        return false;
    }

    private static String extractBearer(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h == null) return null;
        h = h.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return h.substring(7).trim();
        }
        return null;
    }

    private boolean reject(HttpServletResponse response, int status, String error) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", error);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(body));
        return false;
    }
}
