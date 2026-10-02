package com.agridispatch.linebot;

/**
 * 智農派工 - LINE 使用者在某個官方帳號的身分（規格第參章）
 * 身分代碼沿用後端 users.role 與 Android ClientRole／LocalWorkerType／Nationality。
 *
 * @param bound       是否已綁定平台帳號
 * @param role        farmer / worker_local / worker_foreign；未綁定為 null
 * @param subRole     本勞細分：SENIOR / RETURNING_WOMEN / STUDENT / FOREMAN / MACHINERY；其餘為 null
 * @param nationality 外勞國別：VN / ID / TH / PH；其餘為 null
 * @param isLeader    是否為工班組長（與 FOREMAN 同樣可看到工班出勤摘要）
 * @param lang        zh / vi / id
 * @param langLocked  使用者是否主動選過語言（規格 3.2 第 1 點）
 */
public record Identity(boolean bound, String role, String subRole, String nationality,
                       boolean isLeader, String lang, boolean langLocked) {

    public static final String FARMER = "farmer";
    public static final String WORKER_LOCAL = "worker_local";
    public static final String WORKER_FOREIGN = "worker_foreign";

    public static Identity unbound(String lang, boolean langLocked) {
        return new Identity(false, null, null, null, false, lang == null ? "zh" : lang, langLocked);
    }

    public boolean isFarmer() { return bound && FARMER.equals(role); }

    public boolean isWorker() { return bound && (WORKER_LOCAL.equals(role) || WORKER_FOREIGN.equals(role)); }

    public boolean isForeign() { return bound && WORKER_FOREIGN.equals(role); }

    public boolean isMachinery() { return bound && WORKER_LOCAL.equals(role) && "MACHINERY".equals(subRole); }

    /** 帶班師傅或工班組長：任務卡附工班出勤摘要與評核連結 */
    public boolean isCrewLead() {
        return isWorker() && (isLeader || "FOREMAN".equals(subRole));
    }

    public Identity withLang(String newLang, boolean locked) {
        return new Identity(bound, role, subRole, nationality, isLeader, newLang, locked);
    }

    /** 國籍預設語言：VN→vi、ID→id，其餘（本勞、未開放國別）→ zh */
    public static String defaultLangFor(String role, String nationality) {
        if (WORKER_FOREIGN.equals(role)) {
            if ("VN".equalsIgnoreCase(nationality)) return "vi";
            if ("ID".equalsIgnoreCase(nationality)) return "id";
        }
        return "zh";
    }
}
