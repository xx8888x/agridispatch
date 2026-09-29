package com.agridispatch.common;

/**
 * 勞動法規相關常數（集中管理，避免各處寫死數字）
 */
public final class LaborConstants {

    private LaborConstants() {}

    /** 基本工資（月薪）：勞動部公告 2026/1/1 起 29,500 元 */
    public static final int MIN_MONTHLY_WAGE = 29_500;

    /** 基本工資（時薪）：勞動部公告 2026/1/1 起 196 元 */
    public static final int MIN_HOURLY_WAGE = 196;

    /** 農業部外展農務就業獎勵金（每月） */
    // TODO 待農業部確認適用對象
    public static final int AGRI_EMPLOYMENT_BONUS = 8_800;

    /** 平日加班前 2 小時工資加給倍率（勞基法第 24 條：加給 1/3 以上） */
    public static final double OVERTIME_RATE_FIRST_2H = 4.0 / 3.0;

    /** 月薪換算時薪之基準工時（30 日 × 8 小時） */
    public static final int MONTHLY_HOURS_BASIS = 240;

    public static String formatMoney(int amount) {
        return String.format("%,d", amount);
    }
}
