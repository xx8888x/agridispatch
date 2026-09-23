package com.agridispatch;

import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgriDispatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgriDispatchApplication.class, args);
        System.out.println("====================================================");
        System.out.println("🌱 智農派工 (AgriDispatch) Spring Boot 3 + Hibernate 後端服務已就緒！");
        System.out.println("🚀 Spring Boot 版本: " + SpringBootVersion.getVersion());
        System.out.println("📡 服務監聽埠號: http://localhost:3000");
        System.out.println("🔗 RESTful API 基礎路徑: http://localhost:3000/api");
        System.out.println("🗄️ H2 資料庫控制台: http://localhost:3000/h2-console");
        System.out.println("💻 Vue 3 響應式 CRUD 介面: http://localhost:3000/web/vue-crud.html");
        System.out.println("☕ 執行環境: Java " + System.getProperty("java.version"));
        System.out.println("====================================================");
    }
}
