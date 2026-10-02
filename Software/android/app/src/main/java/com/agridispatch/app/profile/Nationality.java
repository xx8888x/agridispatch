package com.agridispatch.app.profile;

import com.agridispatch.app.R;

/**
 * 外籍移工 (外勞) 國別 (分類表 表三)。
 * 同工同酬、不以國籍差別定價；國別只影響介面語言與排班提醒。
 * offered=false 者在身分選擇畫面停用 (泰國、菲律賓：未規劃引進【待確認】)。
 */
public enum Nationality {
    VN("🇻🇳 越南 · Việt Nam", "vi", true, R.string.nation_vn_desc),
    ID("🇮🇩 印尼 · Indonesia", "id", true, R.string.nation_id_desc),
    TH("🇹🇭 泰國 · ประเทศไทย", "", false, R.string.nation_not_offered),
    PH("🇵🇭 菲律賓 · Pilipinas", "", false, R.string.nation_not_offered);

    /** 國名 (中文 + 母語，不隨系統語系翻譯) */
    public final String label;
    /** 前端介面語言代碼；空字串表示系統尚未支援 */
    public final String language;
    public final boolean offered;
    public final int descRes;

    Nationality(String label, String language, boolean offered, int descRes) {
        this.label = label;
        this.language = language;
        this.offered = offered;
        this.descRes = descRes;
    }

    public static Nationality fromName(String name) {
        for (Nationality n : values()) {
            if (n.name().equals(name)) return n;
        }
        return null;
    }
}
