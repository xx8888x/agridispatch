package com.agridispatch.app.profile;

/**
 * 本地工人 (本勞) 人力類型 (分類表 表二)：人才庫與 LINE 共用的分類代碼。
 * App 身分選擇只問 LocalTrack (會進不同頁面的三種)，不問此細分；
 * 此處保留對應關係，並用於把 1.0.6 以前存的類型轉成軌別。
 */
public enum LocalWorkerType {
    SENIOR(LocalTrack.LABOR),
    RETURNING_WOMEN(LocalTrack.LABOR),
    STUDENT(LocalTrack.LABOR),
    FOREMAN(LocalTrack.FOREMAN),
    MACHINERY(LocalTrack.MACHINERY);

    public final LocalTrack track;

    LocalWorkerType(LocalTrack track) {
        this.track = track;
    }

    public static LocalWorkerType fromName(String name) {
        for (LocalWorkerType t : values()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }
}
