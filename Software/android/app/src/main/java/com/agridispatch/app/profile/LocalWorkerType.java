package com.agridispatch.app.profile;

import com.agridispatch.app.R;

/**
 * 本地工人 (本勞) 類型 (分類表 表二)。
 * track 對應前端 worker-local-login.html 的 ?role= 軌別 (labor / foreman / machinery)。
 */
public enum LocalWorkerType {
    SENIOR("🧓", R.string.local_senior, R.string.local_senior_desc, "labor"),
    RETURNING_WOMEN("👩", R.string.local_returning_women, R.string.local_returning_women_desc, "labor"),
    STUDENT("🎓", R.string.local_student, R.string.local_student_desc, "labor"),
    FOREMAN("🧑‍🏫", R.string.local_foreman, R.string.local_foreman_desc, "foreman"),
    MACHINERY("🚜", R.string.local_machinery, R.string.local_machinery_desc, "machinery");

    /** 選擇畫面左側圖示 */
    public final String icon;
    public final int labelRes;
    public final int descRes;
    public final String track;

    LocalWorkerType(String icon, int labelRes, int descRes, String track) {
        this.icon = icon;
        this.labelRes = labelRes;
        this.descRes = descRes;
        this.track = track;
    }

    public static LocalWorkerType fromName(String name) {
        for (LocalWorkerType t : values()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }
}
