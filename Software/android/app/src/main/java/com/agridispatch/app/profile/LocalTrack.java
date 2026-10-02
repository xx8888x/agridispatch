package com.agridispatch.app.profile;

import com.agridispatch.app.R;

/**
 * 本國工人在 App 裡選的工作軌別 (分類表 表二)，對應前端 worker-local-login.html 的 ?role=。
 * 只列「選了會進不同頁面」的三種；中高齡、二度就業、大專生都屬 LABOR，細分見 LocalWorkerType，App 不詢問。
 */
public enum LocalTrack {
    LABOR("labor", R.drawable.ic_wheat, R.string.track_labor, R.string.track_labor_desc),
    FOREMAN("foreman", R.drawable.ic_users, R.string.track_foreman, R.string.track_foreman_desc),
    MACHINERY("machinery", R.drawable.ic_tractor, R.string.track_machinery, R.string.track_machinery_desc);

    /** 前端 ?role= 參數 */
    public final String code;
    public final int iconRes;
    public final int labelRes;
    public final int descRes;

    LocalTrack(String code, int iconRes, int labelRes, int descRes) {
        this.code = code;
        this.iconRes = iconRes;
        this.labelRes = labelRes;
        this.descRes = descRes;
    }

    public static LocalTrack fromName(String name) {
        for (LocalTrack t : values()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }
}
