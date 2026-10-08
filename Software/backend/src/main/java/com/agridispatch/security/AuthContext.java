package com.agridispatch.security;

import com.agridispatch.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 已驗證的 Token 身分（由 AuthInterceptor 放入 request attribute）
 *
 * @param sub          使用者帳號（users.username 或 worker id）
 * @param role         admin_tech / farmer / worker_foreign / worker_local
 * @param ref          關聯編號（農場主 F001、農工 W001、管理員 ADM01）
 * @param demo         是否為 demo-login 簽發之 token
 * @param expEpochSec  到期時間（epoch 秒）
 * @param demoModeOn   目前後端是否為 demo 模式（demo token 在 demo 模式下視同 admin）
 * @param tokenVersion token 簽發時帳號的 token 版本（payload ver；舊 token 無此欄視為 0）
 */
public record AuthContext(String sub, String role, String ref, boolean demo, long expEpochSec, boolean demoModeOn,
                          int tokenVersion) {

    public static final String REQUEST_ATTR = "agri.auth";

    public static final String ROLE_ADMIN = "admin_tech";
    public static final String ROLE_FARMER = "farmer";
    public static final String ROLE_WORKER_FOREIGN = "worker_foreign";
    public static final String ROLE_WORKER_LOCAL = "worker_local";

    /** 真正的 admin_tech，或 demo 模式下的 demo token（規格第 5 節） */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role) || (demo && demoModeOn);
    }

    public boolean isFarmer() { return ROLE_FARMER.equals(role); }

    public boolean isWorker() {
        return ROLE_WORKER_FOREIGN.equals(role) || ROLE_WORKER_LOCAL.equals(role);
    }

    /** 本人（worker id）或 admin */
    public boolean isSelfOrAdmin(String workerId) {
        if (isAdmin()) return true;
        if (workerId == null) return false;
        return workerId.equalsIgnoreCase(ref) || workerId.equalsIgnoreCase(sub);
    }

    public void requireSelfOrAdmin(String workerId) {
        if (!isSelfOrAdmin(workerId)) throw ApiException.forbidden();
    }

    public void requireAdmin() {
        if (!isAdmin()) throw ApiException.forbidden();
    }

    /** 取得目前請求的身分（未登入時為 null） */
    public static AuthContext current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return from(sra.getRequest());
        }
        return null;
    }

    public static AuthContext from(HttpServletRequest request) {
        Object o = request.getAttribute(REQUEST_ATTR);
        return o instanceof AuthContext ctx ? ctx : null;
    }

    /** 取得目前身分，未登入丟 401 */
    public static AuthContext require() {
        AuthContext ctx = current();
        if (ctx == null) throw ApiException.unauthorized();
        return ctx;
    }
}
