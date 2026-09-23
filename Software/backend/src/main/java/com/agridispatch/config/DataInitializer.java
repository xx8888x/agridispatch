package com.agridispatch.config;

import com.agridispatch.service.SeedService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 系統啟動資料初始化器：自動委派 SeedService 灌入擬真假資料
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final SeedService seedService;

    public DataInitializer(SeedService seedService) {
        this.seedService = seedService;
    }

    @Override
    public void run(String... args) {
        seedService.seedAll(false);
    }
}
