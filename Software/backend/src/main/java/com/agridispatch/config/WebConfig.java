package com.agridispatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String currentDir = System.getProperty("user.dir");
        File softwareDir = new File(currentDir).getParentFile();
        if (softwareDir == null || !new File(softwareDir, "web").exists()) {
            softwareDir = new File(currentDir);
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
