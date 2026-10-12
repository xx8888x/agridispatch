package com.agridispatch.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 舊網址相容：前端資料夾已改英文名（c9e7e1d），舊書籤 /web/農業派遣主網站/...、/demo/科技後台/... 301 轉到新路徑。
 */
@Component
public class LegacyPathRedirectFilter extends OncePerRequestFilter {

    private static final Map<String, String> RENAMED = Map.of(
            "農業派遣主網站", "AgriDispatch",
            "科技後台", "TechBackend");
    /** 只給資料夾時的首頁 */
    private static final Map<String, String> HOME = Map.of(
            "AgriDispatch", "index.html",
            "TechBackend", "admin-login.html");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = URLDecoder.decode(request.getRequestURI().substring(request.getContextPath().length()),
                StandardCharsets.UTF_8);
        for (String site : new String[]{"/web/", "/demo/"}) {
            if (!path.startsWith(site)) continue;
            String rest = path.substring(site.length());
            for (Map.Entry<String, String> e : RENAMED.entrySet()) {
                if (rest.equals(e.getKey()) || rest.startsWith(e.getKey() + "/")) {
                    String target = request.getContextPath() + site + e.getValue() + rest.substring(e.getKey().length());
                    if (target.endsWith(e.getValue()) || target.endsWith(e.getValue() + "/")) {
                        target = request.getContextPath() + site + e.getValue() + "/" + HOME.get(e.getValue());
                    }
                    if (request.getQueryString() != null) target += "?" + request.getQueryString();
                    response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
                    response.setHeader("Location", target);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
