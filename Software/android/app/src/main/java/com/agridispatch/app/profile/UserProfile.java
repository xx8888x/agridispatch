package com.agridispatch.app.profile;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 使用者在本機選定的身分。Server 端固定為後台；客戶端由 RoleSelectActivity 選定。
 */
public final class UserProfile {
    public final AppSide side;
    /** 客戶端身分；Server 端為 null */
    public final ClientRole role;
    /** 僅 WORKER_LOCAL 有值 */
    public final LocalWorkerType localType;
    /** 僅 WORKER_FOREIGN 有值 */
    public final Nationality nationality;

    private UserProfile(AppSide side, ClientRole role, LocalWorkerType localType, Nationality nationality) {
        this.side = side;
        this.role = role;
        this.localType = localType;
        this.nationality = nationality;
    }

    public static UserProfile admin() {
        return new UserProfile(AppSide.SERVER, null, null, null);
    }

    public static UserProfile farmer() {
        return new UserProfile(AppSide.CLIENT, ClientRole.FARMER, null, null);
    }

    public static UserProfile localWorker(LocalWorkerType type) {
        return new UserProfile(AppSide.CLIENT, ClientRole.WORKER_LOCAL, type, null);
    }

    public static UserProfile foreignWorker(Nationality nationality) {
        return new UserProfile(AppSide.CLIENT, ClientRole.WORKER_FOREIGN, null, nationality);
    }

    /** 相對於 assets/www/ 的啟動頁 (含查詢參數) */
    public String entryPage() {
        if (side == AppSide.SERVER) {
            return "科技後台/admin-login.html";
        }
        if (role == ClientRole.WORKER_LOCAL && localType != null) {
            return role.entryPage + "?role=" + localType.track;
        }
        return role.entryPage;
    }

    /** 介面語言：外籍移工依國別，其餘中文 */
    public String language() {
        if (nationality != null && !nationality.language.isEmpty()) {
            return nationality.language;
        }
        return "zh";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("side", side.code);
            o.put("role", side == AppSide.SERVER ? "ADMIN" : role.name());
            o.put("localType", localType == null ? "" : localType.name());
            o.put("localTrack", localType == null ? "" : localType.track);
            o.put("nationality", nationality == null ? "" : nationality.name());
            o.put("language", language());
        } catch (JSONException ignored) {
        }
        return o;
    }
}
