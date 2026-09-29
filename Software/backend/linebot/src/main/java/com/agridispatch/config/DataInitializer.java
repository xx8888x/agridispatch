package com.agridispatch.config;

import com.agridispatch.service.DispatchService;
import com.agridispatch.service.SeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 系統啟動資料初始化器：
 * 1. 委派 SeedService 依 ID 補齊示範資料、升級舊版明碼密碼
 * 2. 從 weather_states 讀回天候避險狀態（重啟不遺失）
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final SeedService seedService;
    private final DispatchService dispatchService;
    private final AgriSettings settings;

    public DataInitializer(SeedService seedService, DispatchService dispatchService, AgriSettings settings) {
        this.seedService = seedService;
        this.dispatchService = dispatchService;
        this.settings = settings;
    }

    @Override
    public void run(String... args) {
        seedService.seedAll(false);
        dispatchService.restoreStateFromDb();
        log.info("[AgriDispatch] 執行模式：{}；金流供應者：{}",
                settings.isDemoEnabled() ? "demo（路演展示）" : "production", settings.getPaymentProvider());
    }
}
