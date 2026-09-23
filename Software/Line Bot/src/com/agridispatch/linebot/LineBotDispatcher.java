package com.agridispatch.linebot;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 智農派工 - LINE Bot 訊息分發大腦
 * 負責解析文字指令、維護使用者語言喜好、並產生對應之 Flex Message 或文字回應
 */
public class LineBotDispatcher {
    private static final ConcurrentHashMap<String, String> userLangs = new ConcurrentHashMap<>();

    /**
     * 解析使用者訊息並產生回覆內容 (JSON 字串)
     */
    public static String dispatch(String userId, String userText) {
        String clean = (userText == null ? "" : userText.trim()).toLowerCase();
        String currentLang = userLangs.getOrDefault(userId, "zh");

        // 1. 語言切換指令
        if (clean.contains("越南") || clean.contains("viet") || clean.contains("tiếng việt")) {
            userLangs.put(userId, "vi");
            return FlexMessageBuilder.wrapText("✅ Đã chuyển đổi sang Tiếng Việt. Gõ 'nhiệm vụ' để xem thẻ điều phái!");
        } else if (clean.contains("印尼") || clean.contains("indo") || clean.contains("bahasa")) {
            userLangs.put(userId, "id");
            return FlexMessageBuilder.wrapText("✅ Bahasa telah diubah ke Bahasa Indonesia. Ketik 'tugas' untuk melihat kartu kerja!");
        } else if (clean.contains("中文") || clean.contains("繁體") || clean.contains("chinese")) {
            userLangs.put(userId, "zh");
            return FlexMessageBuilder.wrapText("✅ 已切換為繁體中文介面。請點選六宮格選單或輸入關鍵字查詢！");
        } else if (clean.contains("切換語言") || clean.contains("語言") || clean.contains("language")) {
            return FlexMessageBuilder.wrapText("🌐 請輸入欲切換的語言名稱：\n• 輸入「繁體中文」\n• 輸入「越南語」(Tiếng Việt)\n• 輸入「印尼語」(Bahasa Indonesia)");
        }

        // 2. 今日派工 / 任務卡
        if (clean.contains("派工") || clean.contains("任務") || clean.contains("工單") 
                || clean.contains("task") || clean.contains("nhiệm vụ") || clean.contains("tugas")) {
            // 若為外籍移工語言模式或特定移工帳號，回傳三語任務卡；否則回傳農場主派工單
            if ("vi".equals(currentLang) || "id".equals(currentLang) || clean.contains("移工") || clean.contains("出勤")) {
                return FlexMessageBuilder.getWorkerTaskFlex(currentLang);
            } else {
                return FlexMessageBuilder.getFarmerOrderFlex();
            }
        }

        // 3. 突發暴雨天候避險
        if (clean.contains("避險") || clean.contains("天氣") || clean.contains("暴雨") || clean.contains("雨") 
                || clean.contains("weather") || clean.contains("mưa") || clean.contains("hujan")) {
            return FlexMessageBuilder.getWeatherHedgeFlex();
        }

        // 4. 早鳥工時包
        if (clean.contains("早鳥") || clean.contains("預購") || clean.contains("優惠") || clean.contains("early")) {
            return FlexMessageBuilder.getEarlyBirdFlex();
        }

        // 5. 本國機耕與無人機代噴
        if (clean.contains("機耕") || clean.contains("代噴") || clean.contains("無人機") || clean.contains("曳引機") || clean.contains("代耕")) {
            return FlexMessageBuilder.getMachineryTaskFlex();
        }

        // 6. 薪資津貼與就業獎勵
        if (clean.contains("薪資") || clean.contains("津貼") || clean.contains("8800") 
                || clean.contains("獎勵") || clean.contains("lương") || clean.contains("gaji")) {
            return FlexMessageBuilder.getSalaryBonusFlex();
        }

        // 7. 帳號綁定
        if (clean.contains("綁定") || clean.contains("帳號") || clean.contains("bind")) {
            return FlexMessageBuilder.getBindingFlex(userId);
        }

        // 8. 預設回傳服務導覽說明卡
        return FlexMessageBuilder.getHelpMenuFlex();
    }
}
