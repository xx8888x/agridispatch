package com.agridispatch.config;

import com.agridispatch.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AgriSettings settings;
    private final AuthInterceptor authInterceptor;

    public WebConfig(AgriSettings settings, AuthInterceptor authInterceptor) {
        this.settings = settings;
        this.authInterceptor = authInterceptor;
    }

    /**
     * CORS 白名單（規格第 8 節）：只允許 agri.cors.allowed-origins
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(settings.getCorsAllowedOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Line-Signature")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 從 user.dir（通常為 Software/backend）往上找含 web/ 或 demo/ 的 Software 目錄
        File currentDir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        File softwareDir = currentDir;
        for (File d = currentDir; d != null; d = d.getParentFile()) {
            if (new File(d, "web").isDirectory() || new File(d, "demo").isDirectory()) {
                softwareDir = d;
                break;
            }
        }

        File webDir = new File(softwareDir, "web");
        File demoDir = new File(softwareDir, "demo");

        if (webDir.exists()) {
            registry.addResourceHandler("/web/**")
                    .addResourceLocations("file:" + webDir.getAbsolutePath() + File.separator);
        }
        if (demoDir.exists()) {
            registry.addResourceHandler("/demo/**")
                    .addResourceLocations("file:" + demoDir.getAbsolutePath() + File.separator);
        }
    }
}
