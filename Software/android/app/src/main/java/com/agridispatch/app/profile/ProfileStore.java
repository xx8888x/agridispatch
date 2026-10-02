package com.agridispatch.app.profile;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 身分的本機儲存。使用獨立的 SharedPreferences (AgriDispatchProfile)，
 * 與網頁可寫入的離線快取 (AgriDispatchCache) 分開，網頁無法竄改身分。
 */
public final class ProfileStore {
    private static final String PREFS = "AgriDispatchProfile";
    private static final String KEY_ROLE = "role";
    private static final String KEY_LOCAL_TYPE = "local_type";
    private static final String KEY_NATIONALITY = "nationality";

    private ProfileStore() {
    }

    /** Server 端固定回傳後台身分；客戶端尚未選擇身分時回傳 null。 */
    public static UserProfile load(Context context) {
        if (AppSide.current() == AppSide.SERVER) {
            return UserProfile.admin();
        }
        SharedPreferences p = prefs(context);
        String role = p.getString(KEY_ROLE, "");
        if (ClientRole.FARMER.name().equals(role)) {
            return UserProfile.farmer();
        }
        if (ClientRole.WORKER_LOCAL.name().equals(role)) {
            LocalWorkerType t = LocalWorkerType.fromName(p.getString(KEY_LOCAL_TYPE, ""));
            return t == null ? null : UserProfile.localWorker(t);
        }
        if (ClientRole.WORKER_FOREIGN.name().equals(role)) {
            Nationality n = Nationality.fromName(p.getString(KEY_NATIONALITY, ""));
            // 已停用的國別 (offered=false) 視同未選擇，需重新選
            return n == null || !n.offered ? null : UserProfile.foreignWorker(n);
        }
        return null;
    }

    public static void save(Context context, UserProfile profile) {
        prefs(context).edit()
                .putString(KEY_ROLE, profile.role == null ? "" : profile.role.name())
                .putString(KEY_LOCAL_TYPE, profile.localType == null ? "" : profile.localType.name())
                .putString(KEY_NATIONALITY, profile.nationality == null ? "" : profile.nationality.name())
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
