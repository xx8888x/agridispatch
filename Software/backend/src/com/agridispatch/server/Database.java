package com.agridispatch.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 智農派工 (AgriDispatch) - Java 檔案持久化資料庫引擎 (北部產區樞紐 ╳ 三層混合模型 ╳ 專車物流精算升級版)
 */
public class Database {
    private static Database instance;
    private final Path dbPath;

    // 天候避險大腦狀態
    private String currentScenarioId = "cloudy";
    private boolean isRainMode = false;
    private String currentWeather = "舒適多雲 26°C (微風涼爽宜農作)";
    private String weatherMessage = "🟢 全台天候良好・外展農工出勤率 100% (26/26人在崗)・作業稼動率高";
    private String alertLevel = "normal";
    private int securedRevenue = 62500;
    private int dailyCapacityHrs = 160;
    private final List<Map<String, Object>> regionalWeather = new ArrayList<>();

    // 記憶體資料集合
    private final List<Map<String, Object>> users = new ArrayList<>();
    private final List<Map<String, Object>> crops = new ArrayList<>();
    private final List<Map<String, Object>> dispatchTeams = new ArrayList<>();
    private final List<Map<String, Object>> workers = new ArrayList<>();
    private final List<Map<String, Object>> orders = new ArrayList<>();
    private final List<Map<String, Object>> clockIns = new ArrayList<>();
    private final List<Map<String, Object>> talents = new ArrayList<>();
    private final List<Map<String, Object>> brochures = new ArrayList<>();
    private final List<Map<String, Object>> lineUsers = new ArrayList<>();
    private final List<Map<String, Object>> lineMessages = new ArrayList<>();

    private String lineChannelSecret = "mock_secret_88888888";
    private String lineChannelAccessToken = "mock_token_agridispatch_line_bot";

    public static synchronized Database getInstance() {
        if (instance == null) {
            instance = new Database();
        }
        return instance;
    }

    private Database() {
        String dataDir = System.getProperty("user.dir") + File.separator + "data";
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.dbPath = Paths.get(dataDir, "agridispatch.json");
        initSeeds();
    }

    private void initSeeds() {
        // 0. 四大使用者帳號定義
        users.clear();
        users.add(createUser("worker_foreign", "123456", "Nguyễn Văn Hùng (阮文雄)", "worker_foreign", "W001", "外籍特許外展農工 (Tier 1)"));
        users.add(createUser("worker_local", "123456", "林小祥 (帶工領班) / 陳建宏 (機耕)", "worker_local", "W026", "本國帶工領班與機耕 (Tier 2/3)"));
        users.add(createUser("farmer", "123456", "陳建龍 (阿龍班長)", "farmer", "F001", "八德有機蔬菜產銷班負責人"));
        users.add(createUser("admin_tech", "admin888", "智農科技調度總監", "admin_tech", "ADM01", "科技公司系統調度與數據管理員"));

        // 1. 作物基準庫 (10 大作物)
        crops.clear();
        crops.add(createCrop("taoyuan_veg", "桃園溫室有機蔬菜 (八德/大溪)", 20, 3, 290, "全年365天溫室採收", "採收、精緻挑菜、田間分級修葉、生鮮小包裝", "八德蔬果預冷冷鏈包裝中心 (室內B棟：蔬果預冷、自動光波秤重、外銷包裝)"));
        crops.add(createCrop("scallion", "宜蘭三星青蔥 (三星/壯圍)", 24, 3, 300, "6月 ~ 10月", "拔蔥、專用洗蔥機作業、分級束捆、冷藏保鮮預備", "三星地區農會洗蔥預冷處理場 (水洗機操作、蔥管套袋、冷鏈排板)"));
        crops.add(createCrop("hsinchu_tea", "新竹手採茶/坪林包種茶", 32, 4, 320, "4月 ~ 11月", "一心二葉精緻手採、輕柔採摘、防曬通風保鮮", "峨眉茶葉冷鏈溫控發酵庫 (室內分級挑枝、溫濕度控制包裝)"));
        crops.add(createCrop("xinpu_citrus", "新竹新埔柑橘/柿餅", 26, 3, 300, "10月 ~ 1月", "產季採收、修枝防果刺、削皮整枝、分級裝箱", "新埔農會柑橘蔬果低溫集貨場 (清洗預冷、自動選果分級包裝)"));
        crops.add(createCrop("mango", "愛文芒果 (屏東枋山/台南玉井)", 28, 4, 300, "4月 ~ 7月", "外銷級採收 (留蒂 0.5cm 護果粉)、疏果、防菌套袋", "屏南冷鏈預冷分級場 (清洗預冷、重量自動分級、外銷裝箱)"));
        crops.add(createCrop("onion", "洋蔥 (屏東恆春/高樹)", 32, 4, 290, "1月 ~ 4月", "拔蔥、剪根剪葉、田間裝袋 (每袋 20kg)、搬運上埂", "恆春恆農洋蔥低溫冷藏通風庫 (分級篩選、冷藏排板)"));
        crops.add(createCrop("pepper", "設施彩椒/小黃瓜 (彰化/雲林)", 20, 3, 310, "全年設施溫室", "整枝修芽、溫室精準分批採摘、防蟲網維護", "西螺蔬果集貨冷藏中心 (生鮮小包裝、貼標封口)"));
        crops.add(createCrop("strawberry", "苗栗大湖香水草莓", 25, 3, 310, "12月 ~ 4月", "高架草莓精密疏花疏果、紅熟採收、海綿襯墊防撞裝盒", "大湖蔬果低溫集貨中心 (快速降溫預冷、生鮮冷鏈分級包裝)"));
        crops.add(createCrop("mushroom", "台中新社太空包香菇", 22, 3, 295, "全年環控溫室", "菇傘熟成採收、菌包清理轉化、溫濕度監測", "新社香菇低溫乾燥分級中心 (低溫烘乾排板、光波選別包裝)"));
        crops.add(createCrop("melon", "宜蘭壯圍網紋洋香瓜", 30, 4, 330, "5月 ~ 8月", "溫室一株一果精緻管理、掛果防磨套袋、成熟度測糖採收", "壯圍農會冷藏包裝作業場 (自動甜度光波檢測、精品禮盒排裝)"));

        // 2. 四大調度工班
        dispatchTeams.clear();
        dispatchTeams.add(createTeam("team_a", "工班 A 組 (桃園溫室組)", 7, "阮文雄 (Nguyễn Văn Hùng)", "0988-111-222", "智農 1 號專車 (RBF-8899)", "張大哥 (0912-111-333)", "🌾 八德有機蔬菜專銷班 溫室第 3 區", "有機葉菜採收、修葉分級、生鮮小包裝", "🏢 八德蔬果預冷冷鏈包裝中心 (室內B棟)", "蔬果預冷降溫、自動光波秤重、外銷包裝貼標", "active_field"));
        dispatchTeams.add(createTeam("team_b", "工班 B 組 (跨區專線組)", 8, "林小祥 (帶工領班) / 阿迪 (Agus)", "0918-668-999", "智農 2 號專車 (跨區專線 / RBF-9988)", "李建國 大哥 (0912-222-444)", "🌾 枋山愛文芒果合作園 / 三星青蔥專區", "外銷級留蒂採收、套袋防護、拔蔥洗蔥束捆", "🏢 屏南冷鏈預冷分級場 / 三星農會包裝場", "差壓預冷、自動光波選果、外銷紙箱封裝", "active_field"));
        dispatchTeams.add(createTeam("team_c", "工班 C 組 (機耕無人機隊)", 5, "陳建宏 (雙證照資深機耕師)", "0988-333-444", "智農 3 號工程車 (配大疆 T50 植保機)", "陳建宏", "🌾 新竹峨眉茶園 / 雲林雜糧稻作區", "無人機精準代噴 + 80hp 曳引機深耕碎土", "🏢 智慧農業資材維護所", "無人機超音波噴嘴清洗、RTK 航線校正保養", "active_field"));
        dispatchTeams.add(createTeam("team_d", "工班 D 組 (冷鏈常態組)", 6, "范氏紅 (Phạm Thị Hồng)", "0988-444-555", "智農 4 號保溫專車 (固定專線)", "王大哥 (0912-555-666)", "🏢 八德蔬果預冷冷鏈包裝中心 (室內B棟)", "蔬果預冷降溫、精密秤重分級、冷鏈櫃裝櫃", "🏢 八德蔬果預冷冷鏈包裝中心 (室內B棟)", "蔬果預冷降溫、外銷精密秤重分級、冷鏈櫃裝櫃", "hedged_indoor"));

        // 3. 26 位在籍外展農工與三層人力
        workers.clear();
        String[][] rawWorkers = {
            {"W001", "Nguyễn Văn Hùng", "阮文雄", "越南", "team_a", "true", "tier1_foreign", "28590", "8800", "1200", "AD88123451", "C9823101", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 採收與修葉分級", "dispatched_field", "0988-111-222"},
            {"W002", "Trần Văn Bình", "陳文平", "越南", "team_a", "false", "tier1_foreign", "28590", "8800", "1200", "AD88123452", "C9823102", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 溫室採收裝箱", "dispatched_field", "0988-111-223"},
            {"W003", "Lê Thị Mai", "黎氏梅", "越南", "team_a", "false", "tier1_foreign", "28590", "8800", "1200", "AD88123453", "C9823103", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 生鮮小包裝貼標", "dispatched_field", "0988-111-224"},
            {"W004", "Phạm Văn Đức", "范文德", "越南", "team_a", "false", "tier1_foreign", "28590", "8800", "1200", "AD88123454", "C9823104", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 搬運上埂裝車", "dispatched_field", "0988-111-225"},
            {"W005", "Hoàng Minh Tuấn", "黃明俊", "越南", "team_a", "false", "tier1_foreign", "28590", "8800", "1200", "AD88123455", "C9823105", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 溫室翻土整畦", "dispatched_field", "0988-111-226"},
            {"W006", "Agus Santoso", "阿迪", "印尼", "team_b", "true", "tier1_foreign", "28590", "8800", "1200", "AD77234561", "X8812301", "2027-06-30", "已納保 (勞/健/職災)", "枋山愛文芒果園 套袋疏果與外銷採收", "dispatched_field", "0988-222-333"},
            {"W007", "Budi Setiawan", "布迪", "印尼", "team_b", "false", "tier1_foreign", "28590", "8800", "1200", "AD77234562", "X8812302", "2027-06-30", "已納保 (勞/健/職災)", "枋山愛文芒果園 剪枝留蒂與果品初選", "dispatched_field", "0988-222-334"},
            {"W008", "Dewi Lestari", "蒂薇", "印尼", "team_b", "false", "tier1_foreign", "28590", "8800", "1200", "AD77234563", "X8812303", "2027-06-30", "已納保 (勞/健/職災)", "枋山愛文芒果園 防菌果袋封口", "dispatched_field", "0988-222-335"},
            {"W009", "Eko Prasetyo", "艾科", "印尼", "team_b", "false", "tier1_foreign", "28590", "8800", "1200", "AD77234564", "X8812304", "2027-06-30", "已納保 (勞/健/職災)", "宜蘭三星青蔥田 洗蔥與分級束捆", "dispatched_field", "0988-222-336"},
            {"W010", "Fajar Hidayat", "法賈", "印尼", "team_b", "false", "tier1_foreign", "28590", "8800", "1200", "AD77234565", "X8812305", "2027-06-30", "已納保 (勞/健/職災)", "宜蘭三星青蔥田 拔蔥與田間裝簍", "dispatched_field", "0988-222-337"},
            {"W011", "Gita Pratama", "吉塔", "印尼", "team_b", "false", "tier1_foreign", "28590", "8800", "1200", "AD77234566", "X8812306", "2027-06-30", "已納保 (勞/健/職災)", "宜蘭三星青蔥田 水洗機操作與套袋", "dispatched_field", "0988-222-338"},
            {"W012", "陳建宏", "陳建宏", "本國", "team_c", "true", "tier3_turnkey", "45000", "8800", "3000", "本國身分證", "312456789", "永久有效", "已納保 (勞保/職災/雇主責任險)", "新竹峨眉茶園 植保機無人機精準代噴", "dispatched_field", "0988-333-444"},
            {"W013", "林志強", "林志強", "本國", "team_c", "false", "tier3_turnkey", "42000", "8800", "3000", "本國身分證", "312456790", "永久有效", "已納保 (勞保/職災/雇主責任險)", "大溪溫室群 大疆T50巡檢與代噴", "dispatched_field", "0988-333-445"},
            {"W014", "Đặng Văn Lâm", "鄧文林", "越南", "team_c", "false", "tier1_foreign", "29500", "8800", "1200", "AD88123456", "C9823106", "2027-09-15", "已納保 (勞/健/職災)", "資材維護所 噴嘴超音波清洗與混藥", "dispatched_field", "0988-333-446"},
            {"W015", "Hadi Wijaya", "哈迪", "印尼", "team_c", "false", "tier1_foreign", "29500", "8800", "1200", "AD77234567", "X8812307", "2027-09-15", "已納保 (勞/健/職災)", "機耕隊 曳引機整地翻土隨車輔助", "dispatched_field", "0988-333-447"},
            {"W016", "Phạm Thị Hồng", "范氏紅", "越南", "team_d", "true", "tier1_foreign", "29000", "8800", "1200", "AD88123457", "C9823107", "2027-11-20", "已納保 (勞/健/職災)", "八德蔬果冷鏈包裝中心 外銷精密秤重分級", "hedged_indoor", "0988-444-555"},
            {"W017", "Vũ Thị Hương", "武氏香", "越南", "team_d", "false", "tier1_foreign", "28590", "8800", "1200", "AD88123458", "C9823108", "2027-11-20", "已納保 (勞/健/職災)", "八德蔬果冷鏈中心 預冷降溫與套袋", "hedged_indoor", "0988-444-556"},
            {"W018", "Siti Rahma", "西蒂", "印尼", "team_d", "false", "tier1_foreign", "28590", "8800", "1200", "AD77234568", "X8812308", "2027-11-20", "已納保 (勞/健/職災)", "八德蔬果冷鏈中心 外銷紙箱組裝貼標", "hedged_indoor", "0988-444-557"},
            {"W019", "Nurul Aini", "努魯", "印尼", "team_d", "false", "tier1_foreign", "28590", "8800", "1200", "AD77234569", "X8812309", "2027-11-20", "已納保 (勞/健/職災)", "八德蔬果冷鏈中心 光波選果機操作", "hedged_indoor", "0988-444-558"},
            {"W020", "Somchai Prasert", "宋猜", "泰國", "team_d", "false", "tier1_foreign", "28590", "8800", "1200", "AD66345671", "T5512301", "2027-12-10", "已納保 (勞/健/職災)", "冷鏈常態組 低溫冷藏庫棧板堆疊", "hedged_indoor", "0988-444-559"},
            {"W021", "黃慶龍", "黃慶龍", "本國", "team_a", "false", "tier2_local_flex", "33000", "8800", "2000", "本國身分證", "312456791", "永久有效", "已納保 (勞保/職災險)", "桃園八德 兼職採收與運搬支援", "standby", "0911-333-222"},
            {"W022", "李麗珠", "李麗珠", "本國", "team_a", "false", "tier2_local_flex", "31000", "8800", "1800", "本國身分證", "312456792", "永久有效", "已納保 (勞保/職災險)", "大溪彩椒園 彈性兼職整枝摘果", "standby", "0922-444-333"},
            {"W023", "Maria Santos", "瑪麗亞", "菲律賓", "team_b", "false", "tier1_foreign", "28590", "8800", "1200", "AD55456781", "P4412301", "2028-01-15", "已納保 (勞/健/職災)", "枋山愛文芒果產區 外銷果品裝箱", "dispatched_field", "0988-222-339"},
            {"W024", "Korn Somsak", "阿頌", "泰國", "team_b", "false", "tier1_foreign", "28590", "8800", "1200", "AD66345672", "T5512302", "2028-01-15", "已納保 (勞/健/職災)", "宜蘭三星青蔥專區 清晨拔蔥出勤", "dispatched_field", "0988-222-340"},
            {"W025", "張天成", "張天成", "本國", "team_c", "false", "tier3_turnkey", "43500", "8800", "3000", "本國身分證", "312456793", "永久有效", "已納保 (勞保/職災/雇主責任險)", "水稻與雜糧專區 80馬力曳引機深耕整地", "dispatched_field", "0933-555-666"},
            {"W026", "林小祥", "林小祥", "本國", "team_b", "true", "tier2_local_flex", "42000", "8000", "2800", "本國身分證", "312456794", "永久有效", "已納保 (勞保/職災/雇主責任險)", "外展帶隊小工頭 帶領6名外勞於枋山芒果園作業與品質驗收", "dispatched_field", "0918-668-999"}
        };
        for (String[] rw : rawWorkers) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", rw[0]);
            w.put("name", rw[1]);
            w.put("chineseName", rw[2]);
            w.put("nationality", rw[3]);
            w.put("teamId", rw[4]);
            w.put("isLeader", Boolean.parseBoolean(rw[5]));
            w.put("leader", Boolean.parseBoolean(rw[5]));
            w.put("tier", rw[6]);
            w.put("baseSalary", Integer.parseInt(rw[7]));
            w.put("agriBonus", Integer.parseInt(rw[8]));
            w.put("transportAllowance", Integer.parseInt(rw[9]));
            w.put("arcNumber", rw[10]);
            w.put("passportNumber", rw[11]);
            w.put("permitExpiry", rw[12]);
            w.put("insuranceStatus", rw[13]);
            w.put("currentTask", rw[14]);
            w.put("dispatchStatus", rw[15]);
            w.put("phone", rw[16]);
            workers.add(w);
        }

        // 4. 12 筆派工訂單
        orders.clear();
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-001");
            ord.put("farmerName", "陳建龍 (阿龍班長)");
            ord.put("phone", "0912-345-678");
            ord.put("farmLocation", "桃園市八德區興豐路有機蔬菜專區");
            ord.put("cropId", "taoyuan_veg");
            ord.put("cropName", "桃園溫室有機蔬菜 (八德)");
            ord.put("area", 5);
            ord.put("plan", "early_bird");
            ord.put("planName", "【Tier 1】產季早鳥工時包 (9折保證出工)");
            ord.put("dispatchCategory", "harvest_grading");
            ord.put("categoryName", "🥬 【採收分級】田間採收與外銷分級");
            ord.put("foremanName", "廖國安 (八德三號棚領班)");
            ord.put("foremanPhone", "0932-111-222");
            ord.put("foremanNotes", "進場請先至 3 號棚工具室報到，由廖領班分派菜畦");
            ord.put("transportZone", "core_35km");
            ord.put("transportSurcharge", 0);
            ord.put("totalHours", 100);
            ord.put("suggestedWorkers", 3);
            ord.put("unitRate", 290);
            ord.put("standardAmount", 29000);
            ord.put("finalAmount", 26100);
            ord.put("totalPrice", 26100);
            ord.put("depositAmount", 5220);
            ord.put("depositPaid", true);
            ord.put("status", "confirmed");
            ord.put("assignedTeamId", "team_a");
            ord.put("createdAt", "2026-09-18 08:30:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-002");
            ord.put("farmerName", "林友財 (三星青蔥契作組長)");
            ord.put("phone", "0921-888-777");
            ord.put("farmLocation", "宜蘭縣三星鄉大隱村青蔥契作田");
            ord.put("cropId", "scallion");
            ord.put("cropName", "宜蘭三星青蔥 (三星/壯圍)");
            ord.put("area", 8);
            ord.put("plan", "early_bird");
            ord.put("planName", "【Tier 1】產季早鳥工時包 (9折保證出工)");
            ord.put("dispatchCategory", "harvest_grading");
            ord.put("categoryName", "🥬 【採收分級】田間採收與外銷分級");
            ord.put("foremanName", "林友財 (本人帶隊)");
            ord.put("foremanPhone", "0921-888-777");
            ord.put("foremanNotes", "水洗機已備妥，請直接至水洗場分組出工");
            ord.put("transportZone", "cross_region");
            ord.put("transportSurcharge", 1200);
            ord.put("totalHours", 192);
            ord.put("suggestedWorkers", 4);
            ord.put("unitRate", 300);
            ord.put("standardAmount", 58800);
            ord.put("finalAmount", 53040);
            ord.put("totalPrice", 53040);
            ord.put("depositAmount", 10368);
            ord.put("depositPaid", true);
            ord.put("status", "in_progress");
            ord.put("assignedTeamId", "team_b");
            ord.put("createdAt", "2026-09-19 09:15:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-003");
            ord.put("farmerName", "張金泉 (峨眉東方美人茶園)");
            ord.put("phone", "0932-111-999");
            ord.put("farmLocation", "新竹縣峨眉鄉石井村茶園第2區");
            ord.put("cropId", "hsinchu_tea");
            ord.put("cropName", "新竹手採茶/坪林包種茶");
            ord.put("area", 3.5);
            ord.put("plan", "turnkey");
            ord.put("planName", "【Tier 3】專業成果統包代耕 (驗收付款)");
            ord.put("dispatchCategory", "drone_pest_control");
            ord.put("categoryName", "🚁 【科技植保】無人機精準噴藥施肥");
            ord.put("foremanName", "張春輝 (茶園現場組長)");
            ord.put("foremanPhone", "0935-222-333");
            ord.put("foremanNotes", "無人機作業前需向張組長確認避風航線與高壓電線位置");
            ord.put("transportZone", "core_35km");
            ord.put("transportSurcharge", 0);
            ord.put("totalHours", 112);
            ord.put("suggestedWorkers", 4);
            ord.put("unitRate", 320);
            ord.put("standardAmount", 35840);
            ord.put("finalAmount", 34048);
            ord.put("totalPrice", 34048);
            ord.put("depositAmount", 10214);
            ord.put("depositPaid", true);
            ord.put("status", "in_progress");
            ord.put("assignedTeamId", "team_c");
            ord.put("createdAt", "2026-09-20 07:45:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-004");
            ord.put("farmerName", "黃世勳 (大溪溫室彩椒產銷園)");
            ord.put("phone", "0918-666-333");
            ord.put("farmLocation", "桃園市大溪區康莊路三段溫室群");
            ord.put("cropId", "pepper");
            ord.put("cropName", "設施彩椒/小黃瓜 (彰化/雲林)");
            ord.put("area", 4);
            ord.put("plan", "hourly_flex");
            ord.put("planName", "【Tier 2】在地彈性兼職包 (隨叫隨派)");
            ord.put("dispatchCategory", "pruning_bagging");
            ord.put("categoryName", "✂️ 【田間管理】修枝疏果與引蔓套袋");
            ord.put("foremanName", "黃世勳 (本人帶隊)");
            ord.put("foremanPhone", "0918-666-333");
            ord.put("foremanNotes", "彩椒棚內已拉好引蔓線，請帶剪定鋏");
            ord.put("transportZone", "core_35km");
            ord.put("transportSurcharge", 0);
            ord.put("totalHours", 80);
            ord.put("suggestedWorkers", 3);
            ord.put("unitRate", 310);
            ord.put("standardAmount", 24800);
            ord.put("finalAmount", 24800);
            ord.put("totalPrice", 24800);
            ord.put("depositAmount", 0);
            ord.put("depositPaid", true);
            ord.put("status", "completed");
            ord.put("assignedTeamId", "team_a");
            ord.put("createdAt", "2026-09-15 10:00:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-005");
            ord.put("farmerName", "枋山愛文芒果產銷第7班");
            ord.put("phone", "0935-777-555");
            ord.put("farmLocation", "屏東縣枋山鄉善餘村芒果專區");
            ord.put("cropId", "mango");
            ord.put("cropName", "愛文芒果 (屏東枋山/台南玉井)");
            ord.put("area", 12);
            ord.put("plan", "early_bird");
            ord.put("planName", "【Tier 1】產季早鳥工時包 (9折保證出工)");
            ord.put("dispatchCategory", "typhoon_emergency");
            ord.put("categoryName", "🚨 【緊急救火】防颱搶收 24H 馳援");
            ord.put("foremanName", "柯志明 (搶收應變指揮)");
            ord.put("foremanPhone", "0920-555-666");
            ord.put("foremanNotes", "🚨 颱風警報搶收，24H分兩班，向柯指揮官領取採收箱");
            ord.put("transportZone", "cross_region");
            ord.put("transportSurcharge", 1200);
            ord.put("totalHours", 336);
            ord.put("suggestedWorkers", 6);
            ord.put("unitRate", 360);
            ord.put("standardAmount", 122160);
            ord.put("finalAmount", 110064);
            ord.put("totalPrice", 110064);
            ord.put("depositAmount", 21773);
            ord.put("depositPaid", false);
            ord.put("status", "pending");
            ord.put("assignedTeamId", "team_b");
            ord.put("createdAt", "2026-09-21 14:10:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-006");
            ord.put("farmerName", "恆春鎮農會契作洋蔥大聯盟");
            ord.put("phone", "0910-222-333");
            ord.put("farmLocation", "屏東縣恆春鎮德和里洋蔥專業區");
            ord.put("cropId", "onion");
            ord.put("cropName", "洋蔥 (屏東恆春/高樹)");
            ord.put("area", 15);
            ord.put("plan", "early_bird");
            ord.put("planName", "【Tier 1】產季早鳥工時包 (9折保證出工)");
            ord.put("dispatchCategory", "tillage_planting");
            ord.put("categoryName", "🚜 【整地種植】曳引機深耕與定植");
            ord.put("foremanName", "陳得勝 (洋蔥班工頭)");
            ord.put("foremanPhone", "0915-444-555");
            ord.put("foremanNotes", "曳引機進場請走東側產業道路，注意灌溉溝渠");
            ord.put("transportZone", "cross_region");
            ord.put("transportSurcharge", 1200);
            ord.put("totalHours", 480);
            ord.put("suggestedWorkers", 6);
            ord.put("unitRate", 290);
            ord.put("standardAmount", 140400);
            ord.put("finalAmount", 126480);
            ord.put("totalPrice", 126480);
            ord.put("depositAmount", 25056);
            ord.put("depositPaid", true);
            ord.put("status", "completed");
            ord.put("assignedTeamId", "team_c");
            ord.put("createdAt", "2026-09-12 08:00:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-007");
            ord.put("farmerName", "彭宏基 (新埔照門柑橘園)");
            ord.put("phone", "0928-333-888");
            ord.put("farmLocation", "新竹縣新埔鎮照門里果園");
            ord.put("cropId", "xinpu_citrus");
            ord.put("cropName", "新竹新埔柑橘/柿餅");
            ord.put("area", 6);
            ord.put("plan", "hourly_flex");
            ord.put("planName", "【Tier 2】在地彈性兼職包 (隨叫隨派)");
            ord.put("dispatchCategory", "pruning_bagging");
            ord.put("categoryName", "✂️ 【田間管理】修枝疏果與引蔓套袋");
            ord.put("foremanName", "彭宏基 (本人帶隊)");
            ord.put("foremanPhone", "0928-333-888");
            ord.put("foremanNotes", "柑橘園果袋已置於工寮，修剪枝條集中放置");
            ord.put("transportZone", "core_35km");
            ord.put("transportSurcharge", 0);
            ord.put("totalHours", 156);
            ord.put("suggestedWorkers", 3);
            ord.put("unitRate", 310);
            ord.put("standardAmount", 48360);
            ord.put("finalAmount", 48360);
            ord.put("totalPrice", 48360);
            ord.put("depositAmount", 0);
            ord.put("depositPaid", true);
            ord.put("status", "confirmed");
            ord.put("assignedTeamId", "team_a");
            ord.put("createdAt", "2026-09-22 11:20:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-008");
            ord.put("farmerName", "大湖滿意香水草莓園");
            ord.put("phone", "0919-444-111");
            ord.put("farmLocation", "苗栗縣大湖鄉富興村水尾坪");
            ord.put("cropId", "strawberry");
            ord.put("cropName", "苗栗大湖香水草莓");
            ord.put("area", 4.5);
            ord.put("plan", "hourly_flex");
            ord.put("planName", "【Tier 2】在地彈性兼職包 (隨叫隨派)");
            ord.put("dispatchCategory", "harvest_grading");
            ord.put("categoryName", "🥬 【採收分級】田間採收與外銷分級");
            ord.put("foremanName", "張金福 (草莓棚領班)");
            ord.put("foremanPhone", "0919-666-777");
            ord.put("foremanNotes", "草莓採收需輕拿輕放，九分熟紅果優先挑選");
            ord.put("transportZone", "cross_region");
            ord.put("transportSurcharge", 1200);
            ord.put("totalHours", 113);
            ord.put("suggestedWorkers", 3);
            ord.put("unitRate", 310);
            ord.put("standardAmount", 36230);
            ord.put("finalAmount", 36230);
            ord.put("totalPrice", 36230);
            ord.put("depositAmount", 0);
            ord.put("depositPaid", false);
            ord.put("status", "pending");
            ord.put("assignedTeamId", "team_a");
            ord.put("createdAt", "2026-09-22 16:40:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-009");
            ord.put("farmerName", "新社香菇智慧產銷班");
            ord.put("phone", "0937-555-888");
            ord.put("farmLocation", "台中市新社區協成里香菇溫室");
            ord.put("cropId", "mushroom");
            ord.put("cropName", "台中新社太空包香菇");
            ord.put("area", 6);
            ord.put("plan", "early_bird");
            ord.put("planName", "【Tier 1】產季早鳥工時包 (9折保證出工)");
            ord.put("dispatchCategory", "cold_chain_pack");
            ord.put("categoryName", "❄️ 【冷鏈包裝】室內預冷與生鮮截切");
            ord.put("foremanName", "李添木 (溫控班長)");
            ord.put("foremanPhone", "0937-111-333");
            ord.put("foremanNotes", "進入預冷庫請著保暖外套，先做截切後入庫");
            ord.put("transportZone", "cross_region");
            ord.put("transportSurcharge", 1200);
            ord.put("totalHours", 132);
            ord.put("suggestedWorkers", 3);
            ord.put("unitRate", 295);
            ord.put("standardAmount", 40140);
            ord.put("finalAmount", 36246);
            ord.put("totalPrice", 36246);
            ord.put("depositAmount", 7009);
            ord.put("depositPaid", true);
            ord.put("status", "confirmed");
            ord.put("assignedTeamId", "team_d");
            ord.put("createdAt", "2026-09-20 13:15:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-010");
            ord.put("farmerName", "壯圍網紋洋香瓜示範園");
            ord.put("phone", "0922-999-000");
            ord.put("farmLocation", "宜蘭縣壯圍鄉新南村瓜棚");
            ord.put("cropId", "melon");
            ord.put("cropName", "宜蘭壯圍網紋洋香瓜");
            ord.put("area", 5);
            ord.put("plan", "early_bird");
            ord.put("planName", "【Tier 1】產季早鳥工時包 (9折保證出工)");
            ord.put("dispatchCategory", "pruning_bagging");
            ord.put("categoryName", "✂️ 【田間管理】修枝疏果與引蔓套袋");
            ord.put("foremanName", "吳政宏 (瓜棚領工)");
            ord.put("foremanPhone", "0922-333-444");
            ord.put("foremanNotes", "網紋瓜疏果一株留一果，請依標記修剪");
            ord.put("transportZone", "cross_region");
            ord.put("transportSurcharge", 1200);
            ord.put("totalHours", 150);
            ord.put("suggestedWorkers", 4);
            ord.put("unitRate", 330);
            ord.put("standardAmount", 50700);
            ord.put("finalAmount", 45750);
            ord.put("totalPrice", 45750);
            ord.put("depositAmount", 8910);
            ord.put("depositPaid", false);
            ord.put("status", "cancelled");
            ord.put("assignedTeamId", "team_b");
            ord.put("createdAt", "2026-09-17 15:30:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-011");
            ord.put("farmerName", "蘆竹水耕智慧農場");
            ord.put("phone", "0911-333-222");
            ord.put("farmLocation", "桃園市蘆竹區南山路溫室二廠");
            ord.put("cropId", "taoyuan_veg");
            ord.put("cropName", "桃園溫室有機蔬菜 (八德/大溪)");
            ord.put("area", 3);
            ord.put("plan", "hourly_flex");
            ord.put("planName", "【Tier 2】在地彈性兼職包 (隨叫隨派)");
            ord.put("dispatchCategory", "cold_chain_pack");
            ord.put("categoryName", "❄️ 【冷鏈包裝】室內預冷與生鮮截切");
            ord.put("foremanName", "林佳蓉 (冷鏈場主管)");
            ord.put("foremanPhone", "0911-888-999");
            ord.put("foremanNotes", "八德二廠冷鏈包裝線，全日室內空調作業");
            ord.put("transportZone", "core_35km");
            ord.put("transportSurcharge", 0);
            ord.put("totalHours", 60);
            ord.put("suggestedWorkers", 2);
            ord.put("unitRate", 310);
            ord.put("standardAmount", 18600);
            ord.put("finalAmount", 18600);
            ord.put("totalPrice", 18600);
            ord.put("depositAmount", 0);
            ord.put("depositPaid", true);
            ord.put("status", "in_progress");
            ord.put("assignedTeamId", "team_d");
            ord.put("createdAt", "2026-09-21 08:20:00");
            orders.add(ord);
        }
        {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("id", "ORD-2026-012");
            ord.put("farmerName", "彰化溪州蔬菜契作合作社");
            ord.put("phone", "0938-111-666");
            ord.put("farmLocation", "彰化縣溪州鄉中央路蔬菜專區");
            ord.put("cropId", "pepper");
            ord.put("cropName", "設施彩椒/小黃瓜 (彰化/雲林)");
            ord.put("area", 7.5);
            ord.put("plan", "turnkey");
            ord.put("planName", "【Tier 3】專業成果統包代耕 (驗收付款)");
            ord.put("dispatchCategory", "tillage_planting");
            ord.put("categoryName", "🚜 【整地種植】曳引機深耕與定植");
            ord.put("foremanName", "許世芳 (溪州機耕隊長)");
            ord.put("foremanPhone", "0938-777-888");
            ord.put("foremanNotes", "大型曳引機翻土作業，請依地籍圖作畦");
            ord.put("transportZone", "cross_region");
            ord.put("transportSurcharge", 1200);
            ord.put("totalHours", 150);
            ord.put("suggestedWorkers", 4);
            ord.put("unitRate", 310);
            ord.put("standardAmount", 47700);
            ord.put("finalAmount", 45375);
            ord.put("totalPrice", 45375);
            ord.put("depositAmount", 13253);
            ord.put("depositPaid", true);
            ord.put("status", "completed");
            ord.put("assignedTeamId", "team_c");
            ord.put("createdAt", "2026-09-10 09:00:00");
            orders.add(ord);
        }

        // 5. 8 位人才庫儲備
        talents.clear();
        {
            Map<String, Object> tal = new LinkedHashMap<>();
            tal.put("id", "TAL-001");
            tal.put("name", "陳文義");
            tal.put("category", "local_driver");
            tal.put("categoryName", "本國大型農機/曳引機駕駛員 (Tier 3)");
            tal.put("phone", "0933-222-111");
            tal.put("experience", "具備 80 馬力曳引機 5 年耕作經驗、北部蔬菜溫室整地熟練");
            tal.put("pdpaConsent", true);
            tal.put("registeredAt", "undefined");
            talents.add(tal);
        }
        {
            Map<String, Object> tal = new LinkedHashMap<>();
            tal.put("id", "TAL-002");
            tal.put("name", "劉家銘");
            tal.put("category", "drone_pilot");
            tal.put("categoryName", "本國專業植保機/無人機手 (Tier 3)");
            tal.put("phone", "0925-666-888");
            tal.put("experience", "持有民航局無人機農藥代噴雙證照、熟悉大疆 T50 系統與航線規劃");
            tal.put("pdpaConsent", true);
            tal.put("registeredAt", "undefined");
            talents.add(tal);
        }
        {
            Map<String, Object> tal = new LinkedHashMap<>();
            tal.put("id", "TAL-003");
            tal.put("name", "Trần Thị Lan (陳氏蘭)");
            tal.put("category", "transfer_worker");
            tal.put("categoryName", "合法期滿中途轉出外展移工 (Tier 1)");
            tal.put("phone", "0981-234-567");
            tal.put("experience", "在台三年蔬果採收與溫室管理經驗、國語溝通流利、工作態度認真");
            tal.put("pdpaConsent", true);
            tal.put("registeredAt", "undefined");
            talents.add(tal);
        }
        {
            Map<String, Object> tal = new LinkedHashMap<>();
            tal.put("id", "TAL-004");
            tal.put("name", "郭寶蓮");
            tal.put("category", "local_flex");
            tal.put("categoryName", "本國二度就業/彈性兼職農工 (Tier 2)");
            tal.put("phone", "0917-888-222");
            tal.put("experience", "大溪在地農民、熟悉溫室瓜果疏果修枝與包裝作業、隨叫隨派");
            tal.put("pdpaConsent", true);
            tal.put("registeredAt", "undefined");
            talents.add(tal);
        }
        {
            Map<String, Object> tal = new LinkedHashMap<>();
            tal.put("id", "TAL-005");
            tal.put("name", "張建國");
            tal.put("category", "local_driver");
            tal.put("categoryName", "本國大型農機/曳引機駕駛員 (Tier 3)");
            tal.put("phone", "0936-777-333");
            tal.put("experience", "熟悉乘坐式插秧機與履帶式迴轉機、具備南部洋蔥田整地經驗");
            tal.put("pdpaConsent", true);
            tal.put("registeredAt", "undefined");
            talents.add(tal);
        }
        {
            Map<String, Object> tal = new LinkedHashMap<>();
            tal.put("id", "TAL-006");
            tal.put("name", "Bambang Wijaya (班邦)");
            tal.put("category", "transfer_worker");
            tal.put("categoryName", "合法期滿中途轉出外展移工 (Tier 1)");
            tal.put("phone", "0975-333-111");
            tal.put("experience", "印尼籍農工、熟悉茶園採收修剪與噴霧機具操作、體力充沛");
            tal.put("pdpaConsent", true);
            tal.put("registeredAt", "undefined");
            talents.add(tal);
        }
        {
            Map<String, Object> tal = new LinkedHashMap<>();
            tal.put("id", "TAL-007");
            tal.put("name", "許秋香");
            tal.put("category", "local_flex");
            tal.put("categoryName", "本國二度就業/彈性兼職農工 (Tier 2)");
            tal.put("phone", "0929-111-444");
            tal.put("experience", "八德有機產銷班包裝線熟手、光波秤重與外銷貼標作業專精");
            tal.put("pdpaConsent", true);
            tal.put("registeredAt", "undefined");
            talents.add(tal);
        }
        {
            Map<String, Object> tal = new LinkedHashMap<>();
            tal.put("id", "TAL-008");
            tal.put("name", "王冠宇");
            tal.put("category", "drone_pilot");
            tal.put("categoryName", "本國專業植保機/無人機手 (Tier 3)");
            tal.put("phone", "0963-888-999");
            tal.put("experience", "具備大面積果園與茶園無人機低空精準代噴 3 年經驗");
            tal.put("pdpaConsent", true);
            tal.put("registeredAt", "undefined");
            talents.add(tal);
        }

        // 6. 4 款產品文宣 DM
        brochures.clear();
        {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", "dm-mango-2026");
            dm.put("title", "【屏東枋山外銷愛文芒果】產季早鳥保證出工 60 工時包");
            dm.put("subtitle", "告別產季搶工被放鳥！合法特許外展工班 ╳ 產季保證出工 ╳ 梅雨冷鏈避險");
            dm.put("category", "採收早鳥");
            dm.put("crop", "愛文芒果");
            dm.put("region", "屏東枋山、台南玉井");
            dm.put("targetAudience", "外銷供果園、全聯契作戶、外銷貿易商契作園");
            dm.put("badge", "★ 產銷班長指名推薦款");
            dm.put("icon", "🥭");
            dm.put("themeColor", "emerald");
            dm.put("coverImage", "https://images.unsplash.com/photo-1553279768-865429fa0078?w=800&auto=format&fit=crop&q=80");
            dm.put("originalPrice", 18000);
            dm.put("discountPrice", 16200);
            dm.put("depositRate", 20);
            dm.put("depositAmount", 3240);
            dm.put("hours", 60);
            dm.put("workers", 4);
            dm.put("days", 2);
            dm.put("phone", "08-876-1234 / 0912-345-678");
            dm.put("serviceOffice", "屏東縣枋山鄉加祿路 128 號");
            dm.put("status", "active");
            brochures.add(dm);
        }
        {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", "dm-greenhouse-2026");
            dm.put("title", "【桃園/雲林設施有機溫室】全年常態巡檢與蔬果採收包裝 120 工時包");
            dm.put("subtitle", "水耕與有機溫室首選！365天穩定固定工班 ╳ 定植採收分級一貫化 ╳ 供應營養午餐零斷線");
            dm.put("category", "設施溫室");
            dm.put("crop", "有機溫室蔬果");
            dm.put("region", "桃園八德、大溪、雲林西螺");
            dm.put("targetAudience", "大台北有機蔬菜供銷班、全聯有機水耕專區、國中小營養午餐供應商");
            dm.put("badge", "★ 全年常態合作滿意度 99.8%");
            dm.put("icon", "🥬");
            dm.put("themeColor", "lime");
            dm.put("coverImage", "https://images.unsplash.com/photo-1585320806297-9794b3e4eeae?w=800&auto=format&fit=crop&q=80");
            dm.put("originalPrice", 33600);
            dm.put("discountPrice", 30240);
            dm.put("depositRate", 20);
            dm.put("depositAmount", 6048);
            dm.put("hours", 120);
            dm.put("workers", 6);
            dm.put("days", 4);
            dm.put("phone", "03-368-2111 / 0932-111-222");
            dm.put("serviceOffice", "桃園市八德區興豐路 827 號");
            dm.put("status", "active");
            brochures.add(dm);
        }
        {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", "dm-drone-2026");
            dm.put("title", "【嘉南平原大面積契作農田】大疆 T50 大型無人機植保與 80hp 機耕深耕包");
            dm.put("subtitle", "科技農業正規軍！RTK 公分級高精度導航 ╳ 雙離心大流量噴霧 ╳ 附完整飛行軌跡產銷履歷");
            dm.put("category", "智慧機耕");
            dm.put("crop", "水稻、雜糧、硬質玉米");
            dm.put("region", "雲林元長、嘉義太保、台南後壁");
            dm.put("targetAudience", "大面積契作大農、育苗中心、產銷班、代耕代噴需求農戶");
            dm.put("badge", "★ 科技代噴良率達標保證");
            dm.put("icon", "🛸");
            dm.put("themeColor", "indigo");
            dm.put("coverImage", "https://images.unsplash.com/photo-1527061011665-3652c757a4d4?w=800&auto=format&fit=crop&q=80");
            dm.put("originalPrice", 28800);
            dm.put("discountPrice", 25200);
            dm.put("depositRate", 20);
            dm.put("depositAmount", 5040);
            dm.put("hours", 30);
            dm.put("workers", 2);
            dm.put("days", 1);
            dm.put("phone", "05-788-5678 / 0918-668-999");
            dm.put("serviceOffice", "雲林縣元長鄉中山路 66 號");
            dm.put("status", "active");
            brochures.add(dm);
        }
        {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", "dm-coldchain-2026");
            dm.put("title", "【高屏旗山/高樹採後冷鏈物流】生鮮蔬果清洗預冷與外銷截切包裝 80 工時包");
            dm.put("subtitle", "防颱防汛搶收必備！15°C 恆溫無塵分級 ╳ 急速差壓預冷保鮮 ╳ 風雨天候自動轉接零停工");
            dm.put("category", "冷鏈防汛");
            dm.put("crop", "鳳梨、香蕉、洋蔥、青蔥");
            dm.put("region", "高雄旗山、屏東高樹、屏南冷鏈物流園區");
            dm.put("targetAudience", "農會運銷部、外銷貿易商、全聯鮮食截切廠、產銷合作社");
            dm.put("badge", "★ 防颱 24 小時快速馳援保證");
            dm.put("icon", "❄️");
            dm.put("themeColor", "cyan");
            dm.put("coverImage", "https://images.unsplash.com/photo-1542838132-92c53300491e?w=800&auto=format&fit=crop&q=80");
            dm.put("originalPrice", 22000);
            dm.put("discountPrice", 19800);
            dm.put("depositRate", 20);
            dm.put("depositAmount", 3960);
            dm.put("hours", 80);
            dm.put("workers", 5);
            dm.put("days", 2);
            dm.put("phone", "07-661-8888 / 0928-333-444");
            dm.put("serviceOffice", "高雄市旗山區中華路 520 號");
            dm.put("status", "active");
            brochures.add(dm);
        }
    }

    private Map<String, Object> createUser(String username, String password, String name, String role, String refId, String roleDesc) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("username", username);
        u.put("password", password);
        u.put("name", name);
        u.put("role", role);
        u.put("refId", refId);
        u.put("roleDesc", roleDesc);
        return u;
    }

    private Map<String, Object> createCrop(String id, String name, int hours, int workers, int rate,
                                          String season, String task, String indoor) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.put("baseHoursPerFen", hours);
        c.put("workersPerFen", workers);
        c.put("rate", rate);
        c.put("season", season);
        c.put("taskDescription", task);
        c.put("indoorAlternative", indoor);
        return c;
    }

    private Map<String, Object> createTeam(String id, String name, int count, String leader, String phone,
                                          String vehicle, String driver, String normalLoc, String normalTask,
                                          String hedgeLoc, String hedgeTask, String status) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("name", name);
        t.put("workerCount", count);
        t.put("leader", leader);
        t.put("leaderPhone", phone);
        t.put("vehicle", vehicle);
        t.put("driver", driver);
        t.put("normalLocation", normalLoc);
        t.put("normalTask", normalTask);
        t.put("hedgeLocation", hedgeLoc);
        t.put("hedgeTask", hedgeTask);
        t.put("status", status);
        return t;
    }

    public synchronized Map<String, Object> authenticate(String username, String password) {
        for (Map<String, Object> u : users) {
            if (u.get("username").equals(username) && u.get("password").equals(password)) {
                Map<String, Object> res = new LinkedHashMap<>(u);
                res.remove("password");
                return res;
            }
        }
        return null;
    }

    public synchronized List<Map<String, Object>> getCrops() { return new ArrayList<>(crops); }
    public synchronized List<Map<String, Object>> getDispatchTeams() { return new ArrayList<>(dispatchTeams); }
    public synchronized List<Map<String, Object>> getWorkers() { return new ArrayList<>(workers); }
    public synchronized List<Map<String, Object>> getOrders() { return new ArrayList<>(orders); }
    public synchronized List<Map<String, Object>> getTalents() { return new ArrayList<>(talents); }
    public synchronized List<Map<String, Object>> getBrochures() { return new ArrayList<>(brochures); }
    public synchronized Map<String, Object> getBrochureById(String id) {
        for (Map<String, Object> b : brochures) {
            if (id.equals(b.get("id"))) return b;
        }
        return null;
    }
    public synchronized Map<String, Object> addBrochure(Map<String, Object> b) {
        if (!b.containsKey("id") || b.get("id") == null) {
            b.put("id", "dm-" + System.currentTimeMillis());
        }
        brochures.add(b);
        return b;
    }
    public synchronized boolean updateBrochure(String id, Map<String, Object> b) {
        for (int i = 0; i < brochures.size(); i++) {
            if (id.equals(brochures.get(i).get("id"))) {
                brochures.set(i, b);
                return true;
            }
        }
        return false;
    }
    public synchronized boolean deleteBrochure(String id) {
        return brochures.removeIf(b -> id.equals(b.get("id")));
    }


    public synchronized Map<String, Object> getCropById(String id) {
        for (Map<String, Object> c : crops) {
            if (id.equals(c.get("id"))) return c;
        }
        return crops.get(0);
    }

    public synchronized Map<String, Object> getWorkerById(String id) {
        for (Map<String, Object> w : workers) {
            if (id.equals(w.get("id"))) return w;
        }
        return workers.get(0);
    }

    public synchronized Map<String, Object> getTeamById(String id) {
        for (Map<String, Object> t : dispatchTeams) {
            if (id.equals(t.get("id"))) return t;
        }
        return dispatchTeams.get(0);
    }

    public synchronized Map<String, Object> addOrder(Map<String, Object> order) {
        order.put("id", "ORD-2026-" + String.format("%03d", orders.size() + 1));
        order.put("createdAt", new Date().toString());
        orders.add(order);
        return order;
    }

    public synchronized Map<String, Object> addClockIn(Map<String, Object> clockIn) {
        clockIn.put("id", "CLK-" + System.currentTimeMillis());
        clockIn.put("clockInTime", new Date().toString());
        clockIns.add(clockIn);
        return clockIn;
    }

    public synchronized Map<String, Object> addTalent(Map<String, Object> talent) {
        talent.put("id", "TAL-" + String.format("%03d", talents.size() + 1));
        talent.put("registeredAt", new Date().toString());
        talents.add(talent);
        return talent;
    }

    // LINE Bot 相關存取方法
    public synchronized String getLineChannelSecret() { return lineChannelSecret; }
    public synchronized String getLineChannelAccessToken() { return lineChannelAccessToken; }
    public synchronized void setLineCredentials(String secret, String token) {
        this.lineChannelSecret = secret;
        this.lineChannelAccessToken = token;
    }

    public synchronized void addLineMessage(Map<String, Object> msg) {
        msg.put("id", "MSG-" + System.currentTimeMillis());
        msg.put("timestamp", new Date().toString());
        lineMessages.add(msg);
    }

    public synchronized List<Map<String, Object>> getLineMessages() {
        return new ArrayList<>(lineMessages);
    }

    public synchronized List<Map<String, Object>> getLineUsers() {
        return new ArrayList<>(lineUsers);
    }

    public synchronized Map<String, Object> findLineUserByLineId(String lineUserId) {
        for (Map<String, Object> u : lineUsers) {
            if (lineUserId.equals(u.get("lineUserId"))) return u;
        }
        return null;
    }

    public synchronized void saveLineUser(Map<String, Object> user) {
        lineUsers.removeIf(u -> Objects.equals(u.get("lineUserId"), user.get("lineUserId")));
        lineUsers.add(user);
    }

    public synchronized boolean isRainMode() { return isRainMode; }
    public synchronized String getCurrentWeather() { return currentWeather; }
    public synchronized String getWeatherMessage() { return weatherMessage; }
    public synchronized String getCurrentScenarioId() { return currentScenarioId; }
    public synchronized String getAlertLevel() { return alertLevel; }
    public synchronized int getSecuredRevenue() { return securedRevenue; }
    public synchronized int getDailyCapacityHrs() { return dailyCapacityHrs; }
    public synchronized List<Map<String, Object>> getRegionalWeather() {
        if (regionalWeather.isEmpty()) {
            initRegionalWeather();
        }
        return new ArrayList<>(regionalWeather);
    }

    public synchronized void setRainMode(boolean rain) {
        applyWeatherScenario(rain ? "heavy_rain" : "sunny");
    }

    public synchronized Map<String, Object> applyWeatherScenario(String scenarioId) {
        if (scenarioId == null || scenarioId.trim().isEmpty()) {
            scenarioId = "sunny";
        }
        this.currentScenarioId = scenarioId;
        String scenarioName;

        switch (scenarioId) {
            case "sunny":
                scenarioName = "全台晴朗炎熱";
                this.isRainMode = false;
                this.currentWeather = "全台晴朗 31°C (高溫無雨)";
                this.alertLevel = "normal";
                this.weatherMessage = "🟢 全台晴朗田間作業中・5 大核心產區出勤率 100%・注意防曬與定時補水";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (Map<String, Object> t : dispatchTeams) {
                    if (!"team_d".equals(t.get("id"))) {
                        t.put("status", "active_field");
                    } else {
                        t.put("status", "hedged_indoor");
                    }
                }
                break;

            case "cloudy":
                scenarioName = "舒適多雲時晴";
                this.isRainMode = false;
                this.currentWeather = "舒適多雲 26°C (微風涼爽宜農作)";
                this.alertLevel = "normal";
                this.weatherMessage = "🟢 舒適天候正常出勤中・氣溫宜人作業稼動率高・無人機航測與田間管理順暢";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (Map<String, Object> t : dispatchTeams) {
                    if (!"team_d".equals(t.get("id"))) {
                        t.put("status", "active_field");
                    } else {
                        t.put("status", "hedged_indoor");
                    }
                }
                break;

            case "shower":
                scenarioName = "局部短暫對流陣雨";
                this.isRainMode = false;
                this.currentWeather = "短暫陣雨 25°C (對流雨胞逼近)";
                this.alertLevel = "info";
                this.weatherMessage = "⚠️ 局部產區有雨・宜蘭三星與北部啟動半室內洗蔥與預冷彈性備援・枋山雲林維持田間出勤";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (Map<String, Object> t : dispatchTeams) {
                    if ("team_b".equals(t.get("id")) || "team_d".equals(t.get("id"))) {
                        t.put("status", "hedged_indoor");
                    } else {
                        t.put("status", "active_field");
                    }
                }
                break;

            case "heavy_rain":
                scenarioName = "滯留鋒面大暴雨 (避險模式)";
                this.isRainMode = true;
                this.currentWeather = "暴雨警戒 22°C (滯留鋒面豪雨特報)";
                this.alertLevel = "warning";
                this.weatherMessage = "⚡ 突發暴雨避險啟動：全體工班已專車轉派八德蔬果預冷冷鏈包裝線，出勤率 100%，零閒置虧損！保全營收 NT$ 62,500！";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (Map<String, Object> t : dispatchTeams) {
                    t.put("status", "hedged_indoor");
                }
                break;

            case "typhoon":
                scenarioName = "強烈颱風陸警警戒";
                this.isRainMode = true;
                this.currentWeather = "強颱警戒 25°C (陸上強風豪雨特報)";
                this.alertLevel = "danger";
                this.weatherMessage = "🚨 強烈颱風警戒發布：啟動 24H 緊急搶收應變方案，全區設施加固防汛，全員調度至冷鏈物流中心備戰！";
                this.securedRevenue = 85000;
                this.dailyCapacityHrs = 192;
                for (Map<String, Object> t : dispatchTeams) {
                    t.put("status", "hedged_indoor");
                }
                break;

            case "cold_wave":
                scenarioName = "強烈寒流低溫特報";
                this.isRainMode = false;
                this.currentWeather = "寒流低溫 9°C (橙色低溫霜害警報)";
                this.alertLevel = "warning";
                this.weatherMessage = "❄️ 寒流急凍防霜警戒：溫室全面密閉保溫防霜害，田間噴防凍保護劑，工班於恆溫冷鏈中心進行精緻分級";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (Map<String, Object> t : dispatchTeams) {
                    if ("team_d".equals(t.get("id"))) {
                        t.put("status", "hedged_indoor");
                    } else {
                        t.put("status", "active_field");
                    }
                }
                break;

            default:
                scenarioName = "自訂天候狀態";
                break;
        }

        if (this.regionalWeather.isEmpty()) {
            this.regionalWeather.addAll(createScenarioRegions(scenarioId));
        } else {
            List<Map<String, Object>> updated = updateScenarioForCurrentRegions(scenarioId);
            this.regionalWeather.clear();
            this.regionalWeather.addAll(updated);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("scenarioId", this.currentScenarioId);
        res.put("scenarioName", scenarioName);
        res.put("isRainMode", this.isRainMode);
        res.put("currentWeather", this.currentWeather);
        res.put("alertLevel", this.alertLevel);
        res.put("weatherMessage", this.weatherMessage);
        res.put("securedRevenue", this.securedRevenue);
        res.put("dailyCapacityHrs", this.dailyCapacityHrs);
        res.put("regionalWeather", new ArrayList<>(this.regionalWeather));
        res.put("teams", new ArrayList<>(this.dispatchTeams));
        return res;
    }

    public synchronized List<Map<String, Object>> updateRegionalWeather(List<Map<String, Object>> newRegions) {
        if (newRegions != null && !newRegions.isEmpty()) {
            this.regionalWeather.clear();
            this.regionalWeather.addAll(newRegions);
        }
        return new ArrayList<>(this.regionalWeather);
    }

    private List<Map<String, Object>> updateScenarioForCurrentRegions(String scenario) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> card : this.regionalWeather) {
            String region = (String) card.getOrDefault("region", "台灣產區");
            String crop = (String) card.getOrDefault("crop", "核心農作");
            String county = (String) card.getOrDefault("county", "");
            String district = (String) card.getOrDefault("district", "");
            String farmName = (String) card.getOrDefault("farmName", region);
            list.add(deriveJavaWeatherForLocation(region, county, district, farmName, crop, scenario));
        }
        return list;
    }

    private Map<String, Object> deriveJavaWeatherForLocation(String region, String county, String district, String farmName, String crop, String scenario) {
        boolean isSouth = region.contains("屏東") || region.contains("高雄") || region.contains("台南") || county.contains("屏東") || county.contains("高雄") || county.contains("台南");
        boolean isMountain = region.contains("阿里山") || region.contains("鹿谷") || district.contains("阿里山") || district.contains("鹿谷");
        boolean isNorthEast = region.contains("宜蘭") || region.contains("桃園") || region.contains("新竹") || region.contains("花蓮") || county.contains("宜蘭") || county.contains("桃園") || county.contains("新竹") || county.contains("花蓮");
        boolean isTea = crop.contains("茶");
        boolean isVeg = crop.contains("菜") || crop.contains("蔥") || crop.contains("椒") || crop.contains("洋蔥");

        String temp = "26°C";
        String icon = "⛅";
        String condition = "多雲舒適";
        String rainChance = "20%";
        String humidity = "65%";
        String uv = "中量 (5)";
        String advice = "田間例行管理，落實定時修剪與水肥調控。";

        switch (scenario) {
            case "sunny":
                if (isSouth) {
                    temp = "33°C"; icon = "☀️"; condition = "南台灣艷陽晴朗"; rainChance = "10%"; humidity = "62%"; uv = "極高 (10)";
                } else if (isMountain) {
                    temp = "23°C"; icon = "🌤️"; condition = "高山晴朗清涼"; rainChance = "10%"; humidity = "58%"; uv = "過量 (9)";
                } else if (isNorthEast) {
                    temp = "29°C"; icon = "☀️"; condition = "陽光普照微風"; rainChance = "10%"; humidity = "64%"; uv = "高量 (8)";
                } else {
                    temp = "32°C"; icon = "☀️"; condition = "晴空萬里炎熱"; rainChance = "10%"; humidity = "56%"; uv = "過量 (9)";
                }
                if (isTea) advice = "微風涼爽適宜，利於人工採摘一心二葉，適合植保機執行超低空精準追肥巡查。";
                else if (isVeg) advice = "設施溫室雙層遮陽網展開，加強水簾通風降溫，持續精緻修葉採收直送冷鏈。";
                else advice = "果園外銷採收與套袋作業正常推進，正午加強果實遮陰防日燒，適時補充灌溉水。";
                break;

            case "shower":
                if (isNorthEast) {
                    temp = "24°C"; icon = "🌧️"; condition = "局部短暫對流陣雨"; rainChance = "70%"; humidity = "85%"; uv = "低量 (3)";
                } else if (isMountain) {
                    temp = "21°C"; icon = "🌦️"; condition = "山區午後雷陣雨"; rainChance = "60%"; humidity = "82%"; uv = "中量 (5)";
                } else {
                    temp = "30°C"; icon = "⛅"; condition = "晴午後局部對流"; rainChance = "35%"; humidity = "72%"; uv = "過量 (8)";
                }
                if (isTea) advice = "午後陣雨山區易起霧，採青作業提早收工，轉入室內浪菁萎凋與茶葉精緻分級。";
                else if (isVeg) advice = "溫室遮雨簾閉合，防潮排濕開啟，溫室內無風雨阻礙正常採收與水洗。";
                else advice = "田間搶收果品，注意降雨泥濘；若遇豪大雨全員專車轉移至冷鏈包裝線。";
                break;

            case "heavy_rain":
                temp = isMountain ? "18°C" : (isSouth ? "24°C" : "22°C");
                icon = "⛈️";
                condition = "滯留鋒面豪大暴雨";
                rainChance = "95%";
                humidity = "95%";
                uv = "微弱 (1)";
                advice = "田區泥濘安全停工！啟動暴雨避險機制，外展工班專車轉派至周邊冷鏈蔬果集貨包裝線，保全 100% 稼動率！";
                break;

            case "typhoon":
                temp = isMountain ? "20°C" : "25°C";
                icon = "🌀";
                condition = "暴風圈警戒強風驟雨";
                rainChance = "100%";
                humidity = "98%";
                uv = "微弱 (1)";
                advice = "🚨 防颱緊急搶收完成，設施溫網室壓條加固，抽水機待命，工班全員進駐室內集貨場避險包裝！";
                break;

            case "cold_wave":
                if (isMountain) {
                    temp = "5°C"; icon = "❄️"; condition = "高山急凍嚴寒霜害"; rainChance = "20%"; humidity = "65%"; uv = "低量 (3)";
                    advice = "高海拔茶園防霜風扇定時運轉，噴施葉面防凍保護劑，防嫩芽霜害凍死。";
                } else if (isNorthEast) {
                    temp = "9°C"; icon = "❄️"; condition = "寒流低溫特報"; rainChance = "25%"; humidity = "72%"; uv = "低量 (3)";
                    advice = "溫室雙層內膜密閉防寒，加開熱風機防凍傷；農工穿著防寒防水工作服作業。";
                } else {
                    temp = "13°C"; icon = "❄️"; condition = "多雲乾燥強冷空氣"; rainChance = "10%"; humidity = "52%"; uv = "中量 (5)";
                    advice = "果園迎風面架設防風網，傍晚加強灌溉提高土溫，預防冷風落花落果。";
                }
                break;

            case "cloudy":
            default:
                if (isSouth) {
                    temp = "30°C"; icon = "⛅"; condition = "多雲時晴微風"; rainChance = "20%"; humidity = "68%"; uv = "中量 (6)";
                } else if (isMountain) {
                    temp = "22°C"; icon = "⛅"; condition = "多雲涼爽適農作"; rainChance = "20%"; humidity = "65%"; uv = "中量 (5)";
                } else {
                    temp = "26°C"; icon = "⛅"; condition = "舒適多雲溫和"; rainChance = "20%"; humidity = "66%"; uv = "中量 (5)";
                }
                if (isTea) advice = "微風氣候極利於無人機巡檢噴施與精緻一心二葉採收，排程作業順暢。";
                else if (isVeg) advice = "溫室通風良好溫度適宜，進行吊蔓修葉、採收與冷鏈直配。";
                else advice = "果園採收與套袋作業正常推進，果實外觀品相優良，農工出勤滿載。";
                break;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", region);
        m.put("county", county);
        m.put("district", district);
        m.put("farmName", farmName);
        m.put("crop", crop);
        m.put("temp", temp);
        m.put("icon", icon);
        m.put("condition", condition);
        m.put("rainChance", rainChance);
        m.put("humidity", humidity);
        m.put("uv", uv);
        m.put("advice", advice);
        return m;
    }

    private void initRegionalWeather() {
        this.regionalWeather.clear();
        this.regionalWeather.addAll(createScenarioRegions("cloudy"));
    }

    private List<Map<String, Object>> createScenarioRegions(String scenario) {
        List<Map<String, Object>> list = new ArrayList<>();
        switch (scenario) {
            case "sunny":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "33°C", "☀️", "晴朗炎熱", "10%", "65%", "極高 (10)", "套袋疏果與外銷留蒂採收中，注意高溫遮蔭與適時補水。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "29°C", "☀️", "陽光普照", "10%", "62%", "高量 (8)", "溫室雙層遮陽網展開，加強水簾通風降溫，持續精緻修葉採收。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "28°C", "☀️", "晴天微風", "15%", "70%", "高量 (7)", "青蔥生長旺盛，執行拔蔥、水洗分級，排程穩定順暢。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "32°C", "☀️", "晴空萬里", "10%", "58%", "過量 (9)", "溫室整枝落蔓，灌溉管路正常供水，預防日燒果。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "30°C", "☀️", "晴朗微風", "5%", "55%", "高量 (8)", "微風 1 級非常適合無人機植保代噴與精緻一心二葉採收。"));
                break;

            case "shower":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "31°C", "☀️", "晴午後短暫對流", "45%", "72%", "極高 (9)", "套袋疏果中，若遇雷陣雨即刻專車轉移至屏南冷鏈物流園區。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "25°C", "🌧️", "陣雨轉多雲", "60%", "82%", "低量 (3)", "溫室遮雨簾閉合，防潮排濕開啟，溫室內無風雨阻礙正常採收。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "24°C", "🌧️", "陰短暫雷陣雨", "75%", "88%", "低量 (2)", "田間拔蔥提早 1 小時收工，全員轉入農會青蔥冷鏈場操作洗蔥機。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "29°C", "⛅", "晴時多雲", "20%", "66%", "過量 (8)", "溫室整枝摘心，注意防中暑補水與高溫通風。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "26°C", "⛅", "多雲偶陣雨", "35%", "70%", "中量 (5)", "暫緩無人機噴灑作業，轉為室內茶葉分級挑枝。"));
                break;

            case "heavy_rain":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "24°C", "⛈️", "大雨滂沱", "90%", "92%", "微弱 (1)", "田區泥濘停工，工班全員專車進駐屏南冷鏈預冷分級場清洗包裝。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "22°C", "⛈️", "豪雨強降水", "95%", "95%", "微弱 (1)", "八德溫室外部排水啟動，工班全員轉入室內 B 棟冷鏈包裝線。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "21°C", "⛈️", "連續豪大雨", "95%", "96%", "微弱 (1)", "田區防汛排澇，全員移至三星冷鏈中心進行青蔥差壓預冷與封裝。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "23°C", "⛈️", "雷雨交加", "85%", "90%", "微弱 (1)", "巡檢溫室排水溝渠，工班轉移至西螺蔬果集貨冷藏中心包裝。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "22°C", "⛈️", "豪雨雷鳴", "90%", "92%", "微弱 (1)", "戶外全面暫停，機耕隊轉入資材維護所執行無人機深層保養。"));
                break;

            case "typhoon":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "26°C", "🌀", "暴風圈籠罩", "100%", "98%", "微弱 (1)", "🚨 外銷芒果防颱緊急搶收完成，全員進駐屏南冷鏈中心全力裝箱。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "24°C", "🌀", "強風驟雨", "95%", "96%", "微弱 (1)", "溫室壓條加固防風網，排水抽水機待命，採收蔬菜直送冷鏈庫。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "23°C", "🌀", "狂風暴雨", "100%", "99%", "微弱 (1)", "青蔥搶收完成避免折損，全員集貨場通宵進行截切與冷凍保溫。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "25°C", "🌀", "豪雨強陣風", "90%", "95%", "微弱 (1)", "檢查溫室捲揚防強風灌入，工班全員至集貨場支援備援出貨。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "24°C", "🌀", "豪大雨警戒", "95%", "96%", "微弱 (1)", "茶園邊坡排水防塌，機具機耕設備進庫防颱固定。"));
                break;

            case "cold_wave":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "15°C", "❄️", "晴冷乾燥", "10%", "50%", "高量 (7)", "果園迎風面設立防風網，灌溉調節田間地溫，預防冷風落花落果。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "9°C", "❄️", "寒風刺骨", "20%", "60%", "低量 (3)", "溫室雙層內膜密閉保溫，必要時開啟加溫熱風機，防霜害凍傷。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "10°C", "❄️", "陰冷低溫", "30%", "75%", "低量 (2)", "洗蔥水線加裝回溫裝置，農工著防寒保暖防水衣物作業。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "11°C", "❄️", "多雲寒冷", "15%", "55%", "中量 (5)", "設施溫室保溫防寒，午後提早關閉通風口蓄積日照熱能。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "8°C", "❄️", "低溫嚴寒", "10%", "50%", "中量 (5)", "茶園噴施防凍葉面保護劑，高海拔茶區防霜風扇定時運轉。"));
                break;

            case "cloudy":
            default:
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "30°C", "⛅", "多雲時晴", "20%", "68%", "中量 (6)", "果園採收與套袋作業正常推進，果實外觀品相優良。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "26°C", "⛅", "多雲舒適", "20%", "68%", "中量 (5)", "溫室通風良好，持續精緻修葉分級與生鮮冷鏈直配。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "25°C", "⛅", "陰天多雲", "25%", "75%", "低量 (4)", "氣溫涼爽極利拔蔥，洗蔥機水線高效作業中。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "28°C", "⛅", "多雲微風", "15%", "65%", "中量 (6)", "設施內溫度適宜，進行吊蔓修葉與病蟲害巡視。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "26°C", "⛅", "多雲風輕", "15%", "65%", "中量 (5)", "適合大疆 T50 執行大面積超低空精準施肥作業。"));
                break;
        }
        return list;
    }

    private Map<String, Object> createRegionMap(String region, String crop, String temp, String icon,
                                                String condition, String rainChance, String humidity,
                                                String uv, String advice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", region);
        m.put("crop", crop);
        m.put("temp", temp);
        m.put("icon", icon);
        m.put("condition", condition);
        m.put("rainChance", rainChance);
        m.put("humidity", humidity);
        m.put("uv", uv);
        m.put("advice", advice);
        return m;
    }
}
