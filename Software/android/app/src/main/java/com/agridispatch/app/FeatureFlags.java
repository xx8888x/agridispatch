package com.agridispatch.app;

/**
 * 客戶端 App 的功能開關 (集中一處，改值即可上架／下架整個功能，不刪程式)。
 * 對應前端網頁的 app-config.js `features.*` (例如 features.foreignWorkers、features.legalize)。
 */
public final class FeatureFlags {

    /**
     * 外籍移工 (外勞) 入口。
     * 目前下架：先以本國勞工 (正職、兼職、中高齡、打工、學生打工) 為主，外籍移工部分暫不開放。
     * 為 false 時：身分選擇畫面不顯示越南／印尼等外籍工人卡片；
     * 本機已存的外籍身分視同未選擇，開 App 會回到身分選擇畫面 (不會卡在外勞頁)。
     * 恢復方式：改回 true 重新建置即可 (各國是否開放仍由 Nationality.offered 控制；
     * 前端網頁另需把 app-config.js 的 features.foreignWorkers 改回 true)。
     */
    public static final boolean FOREIGN_WORKERS = false;

    private FeatureFlags() {
    }
}
