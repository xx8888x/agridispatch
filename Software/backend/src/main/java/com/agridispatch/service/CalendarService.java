package com.agridispatch.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 全台 52 週農作物產曆、季節動態調度與收穫日倒數服務
 */
@Service
public class CalendarService {

    public List<Map<String, Object>> getQuarterCalendar() {
        return List.of(
            Map.of(
                "quarter", "Q1",
                "name", "第 1 季：冬春高價蔬果與春節檔期",
                "months", "1月 ~ 3月",
                "utilizationRate", "92.0%",
                "climateContext", "南部旱季・氣候溫和適合採收・春節送禮搶工尖峰",
                "primaryRegions", "高屏產區、苗栗大湖、台南關廟、南投信義",
                "keyCrops", List.of("燕巢/高樹牛奶蜜棗", "大湖香水草莓", "枋寮黑珍珠蓮霧", "車城/枋山洋蔥", "關廟金鑽鳳梨", "信義手採青梅", "三峽碧螺春春茶"),
                "dispatchStrategy", "重點投入工班 B 組 (跨區專線) 與工班 A 組，主攻年節高價禮盒精緻包裝與南部早春搶收。"
            ),
            Map.of(
                "quarter", "Q2",
                "name", "第 2 季：春茶大採、初夏芒果與高經濟水果爆發期",
                "months", "4月 ~ 6月",
                "utilizationRate", "98.5%",
                "climateContext", "全台搶工最高峰・茶菁與玉荷包不等人・日薪飆破 $3,500",
                "primaryRegions", "坪林/鹿谷/阿里山茶區、屏東枋山、高雄大樹、彰化大村、大溪",
                "keyCrops", List.of("高山文山包種/烏龍春茶", "枋山愛文芒果 (日韓外銷)", "大樹玉荷包荔枝 (3週黃金期)", "雲林西瓜", "大溪綠竹筍", "北埔東方美人茶", "拉拉山水蜜桃"),
                "dispatchStrategy", "全體工班 100% 滿載運作，由工班 C 組無人機隊執行大面積茶園與果樹預防代噴，工班 A/B 組連軸轉派。"
            ),
            Map.of(
                "quarter", "Q3",
                "name", "第 3 季：盛夏水果王國、高接梨與文旦柚中秋搶收",
                "months", "7月 ~ 9月",
                "utilizationRate", "94.0%",
                "climateContext", "梅雨與颱風好發季・啟動演算法天候避險・室內冷鏈預冷包裝",
                "primaryRegions", "台中東勢/苗栗卓蘭、台南玉井、花蓮富里/台東太麻里、台南麻豆、宜蘭三星",
                "keyCrops", List.of("東勢/卓蘭高接梨", "玉井愛文芒果", "拉拉山水蜜桃", "六十石山/太麻里金針花", "麻豆老欉文旦柚 (白露前搶收)", "宜蘭三星青蔥", "新埔柿餅"),
                "dispatchStrategy", "啟動天候避險大腦，降雨時立即專車改派八德/西螺/麻豆蔬果冷鏈預冷包裝線，出勤率零閒置。"
            ),
            Map.of(
                "quarter", "Q4",
                "name", "第 4 季：高山冬茶、柿餅、秋冬蔬菜與草莓開幕",
                "months", "10月 ~ 12月",
                "utilizationRate", "95.0%",
                "climateContext", "九降風起曬柿餅・高山冬茶採收・二期稻作割稻・草莓第一期花",
                "primaryRegions", "新竹新埔、坪林/阿里山、高雄美濃、台南善化/白河、嘉義新港、全台稻作區",
                "keyCrops", List.of("新埔牛心柿餅", "坪林/阿里山高山冬茶", "美濃白玉蘿蔔", "善化胡麻割株", "新港洋桔梗 (外銷日本)", "大湖草莓第一期花", "二期水稻隨車接穀"),
                "dispatchStrategy", "工班 C 組出動大型收割機與無人機，工班 D 組常態駐紮冷鏈分級，工班 A 組轉戰北部冬作溫室。"
            )
        );
    }

    public List<Map<String, Object>> getMonthlyCalendar() {
        List<Map<String, Object>> list = new ArrayList<>();
        String[] months = {"1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月"};
        String[] quarters = {"Q1", "Q1", "Q1", "Q2", "Q2", "Q2", "Q3", "Q3", "Q3", "Q4", "Q4", "Q4"};
        String[] titles = {
            "蜜棗蓮霧春節搶收・草莓一期花盛開",
            "洋蔥拔蔥風乾・草莓盛產極期・地瓜採收",
            "春茶嫩芽開採・關廟金鑽鳳梨・信義青梅",
            "文山包種/阿里山春茶大搶收・枋山芒果早熟果",
            "玉荷包荔枝 3 週黃金搶收・芒果外銷日韓高峰",
            "清晨破曉掘綠竹筍・東方美人茶・一期水稻收割",
            "高接梨寄接梨・玉井芒果高峰・拉拉山水蜜桃",
            "麻豆文旦白露前搶收・花東金針花海手採",
            "水田採茭白筍・九降風起曬柿餅・百香果盛產",
            "高山冬茶大採收・新埔柿餅滿開・洛神花捅心",
            "美濃白玉蘿蔔・善化胡麻割株・洋桔梗外銷日本",
            "草莓季盛大開幕・黑珍珠蓮霧・蜜棗登場・冬筍"
        };
        String[] regions = {
            "高雄燕巢、屏東枋寮/高樹、苗栗大湖、台北陽明山",
            "屏東枋山/車城、苗栗大湖、雲林水林、南投信義",
            "台南關廟、新北三峽、南投信義、屏東高樹、金門",
            "新北坪林、南投鹿谷、嘉義阿里山、屏東枋山",
            "高雄大樹、屏東枋山、台南玉井、雲林二崙、彰化大村",
            "基隆/士林/大溪、新竹北埔、桃園拉拉山、花東池上",
            "苗栗卓蘭、台中東勢、台南玉井、桃園復興、公館",
            "台南麻豆、新北八里、花蓮富里、台東太麻里、桃園大溪",
            "新北三芝、新竹新埔、南投埔里、嘉義竹崎、金門",
            "新北坪林、南投鹿谷、嘉義梅山、新竹新埔、台東太麻里",
            "高雄美濃、台南善化/白河、嘉義新港、全台稻作平原",
            "苗栗大湖、屏東枋寮、高雄燕巢、南投鹿谷、雲林水林"
        };
        String[] crops = {
            "燕巢蜜棗、枋寮蓮霧、大湖草莓、陽明山山藥、田尾切花",
            "車城洋蔥、大湖草莓二期花、水林台農57號甘藷、新社枇杷",
            "關廟金鑽鳳梨、三峽碧螺春春茶、信義青梅、高樹鳳梨",
            "坪林包種春茶、阿里山春茶、枋山愛文芒果、信義黃梅",
            "大樹玉荷包荔枝、枋山愛文芒果、二崙西瓜、大村葡萄",
            "大溪綠竹筍、北埔東方美人茶、拉拉山水蜜桃、池上米一期",
            "卓蘭高接梨、東勢寄接梨、玉井愛文芒果、公館紅棗",
            "麻豆老欉文旦柚、六十石山金針花、太麻里金針花、大溪韭菜花",
            "三芝茭白筍、新埔柿餅、埔里百香果、金門高粱",
            "坪林/阿里山冬茶、新埔柿餅、太麻里洛神花、基隆山藥",
            "美濃白玉蘿蔔、善化胡麻、新港洋桔梗、二期稻作",
            "大湖草莓第一期花、枋寮蓮霧、燕巢蜜棗、鹿谷冬筍"
        };
        String[] teams = {
            "工班 B 組 (跨區專線) + 工班 A 組",
            "工班 B 組 (跨區專線)",
            "工班 A 組 (桃園溫室) + 工班 B 組",
            "工班 C 組 (機耕無人機隊) + 工班 B 組",
            "工班 B 組 (跨區專線) + 工班 A 組",
            "工班 A 組 (桃園溫室) + 工班 C 組",
            "工班 B 組 (跨區專線)",
            "工班 B 組 (跨區專線) + 工班 D 組 (冷鏈)",
            "工班 A 組 (桃園溫室) + 工班 D 組",
            "工班 C 組 (機耕無人機隊)",
            "工班 C 組 (機耕無人機隊) + 工班 D 組",
            "工班 A 組 (桃園溫室) + 工班 B 組"
        };

        for (int i = 0; i < 12; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", i + 1);
            m.put("monthLabel", months[i]);
            m.put("quarter", quarters[i]);
            m.put("title", titles[i]);
            m.put("regions", regions[i]);
            m.put("crops", crops[i]);
            m.put("suggestedTeam", teams[i]);
            list.add(m);
        }
        return list;
    }

    /**
     * 取得全台關鍵收穫日與倒數清單 (支援依目前年月份輪轉排序)
     */
    public List<Map<String, Object>> getUpcomingHarvests(Integer targetMonth) {
        return getUpcomingHarvests(null, targetMonth);
    }

    public List<Map<String, Object>> getUpcomingHarvests(Integer targetYear, Integer targetMonth) {
        LocalDate now = LocalDate.now();
        int baseYear = (targetYear != null && targetYear >= 2024 && targetYear <= 2035) ? targetYear : now.getYear();
        int baseMonth = (targetMonth != null && targetMonth >= 1 && targetMonth <= 12) ? targetMonth : now.getMonthValue();
        int baseDay = (baseYear == now.getYear() && baseMonth == now.getMonthValue()) ? now.getDayOfMonth() : 15;

        List<Map<String, Object>> allHarvests = new ArrayList<>(List.of(
            createHarvest("H01", "大樹玉荷包荔枝", "高雄市大樹區", 5, 20, "5月20日 ~ 6月05日",
                    "🔥 3週黃金搶收期", "延誤即落果變質！集中搶收、剪葉分級、低溫冷藏", "team_b", "mango", 15),
            createHarvest("H02", "枋山愛文芒果 (日韓外銷)", "屏東縣枋山鄉", 5, 10, "5月10日 ~ 6月25日",
                    "⚡ 早鳥預約外銷高峰", "留蒂 0.5cm 護果粉、重量光波選果、外銷裝箱", "team_b", "mango", 20),
            createHarvest("H03", "拉拉山水蜜桃", "桃園市復興區", 6, 25, "6月20日 ~ 7月25日",
                    "🔥 高山搶收大尖峰", "高海拔手採防碰傷、海綿網套防撞、清晨低溫採摘", "team_a", "taoyuan_veg", 10),
            createHarvest("H04", "東勢寄接梨 / 卓蘭高接梨", "台中市東勢區 / 苗栗卓蘭", 7, 15, "7月10日 ~ 8月20日",
                    "⚡ 產季早鳥搶工期", "套袋果摘除、冷藏預冷保鮮、自動重量分級", "team_b", "taoyuan_veg", 12),
            createHarvest("H05", "麻豆老欉文旦柚 (中秋禮盒)", "台南市麻豆區", 8, 28, "8月20日 ~ 9月10日",
                    "🔥 白露前10天徹夜搶收", "白露前搶收消水、外觀修整、中秋禮盒排裝、颱風防搶", "team_b", "taoyuan_veg", 25),
            createHarvest("H06", "新埔九降風牛心柿餅", "新竹縣新埔鎮", 9, 25, "9月20日 ~ 11月15日",
                    "🌿 穩定加工出勤期", "人工削皮、圓盤日曬、九降風天然風乾、手工捏壓成形", "team_a", "xinpu_citrus", 8),
            createHarvest("H07", "坪林文山包種 / 阿里山高山冬茶", "新北坪林 / 嘉義梅山", 10, 20, "10月15日 ~ 11月10日",
                    "🔥 冬茶黃金嫩芽期", "一心二葉精緻手採、防日曬萎凋、茶菁即時送廠", "team_c", "hsinchu_tea", 18),
            createHarvest("H08", "美濃白玉蘿蔔", "高雄市美濃區", 11, 15, "11月10日 ~ 12月20日",
                    "⚡ 在地觀光與契作搶收", "田間人工拔取、整修泥根、生鮮分級裝箱裝車", "team_c", "taoyuan_veg", 8),
            createHarvest("H09", "大湖香水草莓第一期花", "苗栗縣大湖鄉", 12, 5, "12月01日 ~ 1月15日",
                    "⚡ 冬季草莓季盛大開幕", "高架草莓紅熟採收、防碰傷盒裝、冷鏈預冷直配", "team_a", "strawberry", 15),
            createHarvest("H10", "車城黃金洋蔥 / 恆春洋蔥", "屏東縣車城鄉 / 恆春鎮", 2, 20, "2月10日 ~ 3月31日",
                    "🌿 落山風拔蔥採收期", "拔蔥、剪根剪葉、田間裝袋 (每袋 20kg)、搬運上埂", "team_b", "onion", 16),
            createHarvest("H11", "關廟金鑽鳳梨", "台南市關廟區", 3, 20, "3月15日 ~ 5月10日",
                    "⚡ 早春外銷大採收", "防曬套袋摘除、果目熟度檢定、去冠芽或帶冠裝箱", "team_b", "mango", 14),
            createHarvest("H12", "基隆/士林/大溪綠竹筍", "北部丘陵產區", 6, 10, "5月25日 ~ 7月10日",
                    "🔥 清晨破曉掘筍", "清晨 04:00 摸黑掘筍防出青、冰水預冷鎖甜、生鮮冷藏", "team_a", "taoyuan_veg", 10)
        ));

        // 依據年月份差進行動態時序輪轉排序 (當月與即將到來的月份排在最前)
        allHarvests.sort((a, b) -> {
            int ma = (int) a.get("month");
            int mb = (int) b.get("month");
            int diffA = (ma - baseMonth + 12) % 12;
            int diffB = (mb - baseMonth + 12) % 12;
            if (diffA != diffB) {
                return Integer.compare(diffA, diffB);
            }
            int da = (int) a.get("day");
            int db = (int) b.get("day");
            return Integer.compare(da, db);
        });

        // 動態計算距離預估收穫天數與所屬年度
        for (Map<String, Object> h : allHarvests) {
            int m = (int) h.get("month");
            int d = (int) h.get("day");
            int harvestYear = (m < baseMonth || (m == baseMonth && d < baseDay)) ? baseYear + 1 : baseYear;
            h.put("harvestYear", harvestYear);

            LocalDate harvestDate = LocalDate.of(harvestYear, m, d);
            LocalDate currentDate = LocalDate.of(baseYear, baseMonth, baseDay);
            long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(currentDate, harvestDate);
            if (daysBetween < 0) daysBetween += 365;

            h.put("countdownDays", (int) daysBetween);
            h.put("harvestDateStr", String.format("%d年%d月%d日", harvestYear, m, d));
            
            if (daysBetween <= 15) {
                h.put("statusLabel", "🚨 採收倒數中");
                h.put("statusColor", "rose");
            } else if (daysBetween <= 45) {
                h.put("statusLabel", "⏳ 預約搶工中");
                h.put("statusColor", "amber");
            } else {
                h.put("statusLabel", "🌱 生長監測中");
                h.put("statusColor", "emerald");
            }
        }

        return allHarvests;
    }

    private Map<String, Object> createHarvest(String id, String cropName, String region,
                                             int month, int day, String window,
                                             String urgency, String task,
                                             String teamId, String cropId, int workers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("cropName", cropName);
        m.put("region", region);
        m.put("month", month);
        m.put("day", day);
        m.put("harvestWindow", window);
        m.put("urgencyLevel", urgency);
        m.put("taskDescription", task);
        m.put("suggestedTeamId", teamId);
        m.put("cropId", cropId);
        m.put("neededWorkers", workers);
        return m;
    }
}
