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
    public final LocalTrack localTrack;
    /** 僅 WORKER_FOREIGN 有值 */
    public final Nationality nationality;

    private UserProfile(AppSide side, ClientRole role, LocalTrack localTrack, Nationality nationality) {
        this.side = side;
        this.role = role;
        this.localTrack = localTrack;
        this.nationality = nationality;
    }

    public static UserProfile admin() {
        return new UserProfile(AppSide.SERVER, null, null, null);
    }

    public static UserProfile farmer() {
        return new UserProfile(AppSide.CLIENT, ClientRole.FARMER, null, null);
    }

    public static UserProfile localWorker(LocalTrack track) {
        return new UserProfile(AppSide.CLIENT, ClientRole.WORKER_LOCAL, track, null);
    }

    public static UserProfile foreignWorker(Nationality nationality) {
        return new UserProfile(AppSide.CLIENT, ClientRole.WORKER_FOREIGN, null, nationality);
    }

    /** 相對於 assets/www/ 的啟動頁 (含查詢參數) */
    public String entryPage() {
        if (side == AppSide.SERVER) {
            return "TechBackend/admin-login.html";
        }
        if (role == ClientRole.WORKER_LOCAL && localTrack != null) {
            return role.entryPage + "?role=" + localTrack.code;
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
            // 後端 users.role 代碼 (farmer / worker_local / worker_foreign / admin_tech)
            o.put("platformRole", side == AppSide.SERVER ? "admin_tech" : role.platformRole);
            o.put("localTrack", localTrack == null ? "" : localTrack.code);
            o.put("nationality", nationality == null ? "" : nationality.name());
            o.put("language", language());
        } catch (JSONException ignored) {
        }
        return o;
    }
}
