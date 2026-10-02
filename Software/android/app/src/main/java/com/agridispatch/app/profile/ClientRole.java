package com.agridispatch.app.profile;

import com.agridispatch.app.R;

/**
 * 客戶端身分 (分類表 表一)。
 */
public enum ClientRole {
    FARMER("farmer", R.string.role_pick_farmer, R.string.role_pick_farmer_desc, "農業派遣主網站/farmer-login.html"),
    WORKER_LOCAL("worker_local", R.string.role_pick_worker_local, R.string.role_pick_worker_local_desc, "農業派遣主網站/worker-local-login.html"),
    WORKER_FOREIGN("worker_foreign", R.string.role_pick_worker_foreign, R.string.role_pick_worker_foreign_desc, "農業派遣主網站/worker-foreign-login.html");

    /** 對應後端 users.role */
    public final String platformRole;
    public final int labelRes;
    public final int descRes;
    /** 相對於 assets/www/ 的登入頁路徑 */
    public final String entryPage;

    ClientRole(String platformRole, int labelRes, int descRes, String entryPage) {
        this.platformRole = platformRole;
        this.labelRes = labelRes;
        this.descRes = descRes;
        this.entryPage = entryPage;
    }
}
