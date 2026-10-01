package com.agridispatch.service;

import com.agridispatch.common.LaborConstants;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.*;
import com.agridispatch.repository.*;
import com.agridispatch.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 擬真示範資料種子服務
 * - 「依 ID 補缺」：資料不存在才新增，不覆蓋既有資料（取代舊版 count()==0 才種子）
 * - 帳號密碼一律以 PBKDF2 雜湊儲存，並於啟動時把舊版明碼升級為雜湊
 */
@Service
@Transactional
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    /** demo 模式示範密碼（僅 demo 模式使用） */
    static final String DEMO_PASSWORD = "123456";
    /** demo 模式管理員示範密碼（沿用既有前端示範帳密） */
    static final String DEMO_ADMIN_PASSWORD = "admin888";
    /** 舊版曾寫死的預設密碼：非 demo 模式偵測到時於 log 警告 */
    private static final Set<String> KNOWN_DEFAULT_PASSWORDS = Set.of("123456", "admin888");
    /** 路演來賓帳號清單（含明碼，已 gitignore）；可用環境變數 AGRI_ROADSHOW_ACCOUNTS 指定其他路徑 */
    private static final String DEFAULT_ROADSHOW_ACCOUNTS = "data/roadshow-accounts.csv";
    private static final Set<String> ROADSHOW_ROLES = Set.of("farmer", "worker_foreign", "worker_local", "admin_tech");

    private final UserRepository userRepository;
    private final CropRepository cropRepository;
    private final DispatchTeamRepository teamRepository;
    private final WorkerRepository workerRepository;
    private final OrderRepository orderRepository;
    private final TalentRepository talentRepository;
    private final BrochureRepository brochureRepository;
    private final WeatherStateRepository weatherStateRepository;
    private final AttendanceRepository attendanceRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final TeamRatingRepository teamRatingRepository;
    private final InsuranceRecordRepository insuranceRecordRepository;
    private final PasswordHasher passwordHasher;
    private final AgriSettings settings;

    /** demo 密碼雜湊快取（310,000 次 PBKDF2 較耗時，demo 種子共用同一組雜湊） */
    private String demoPasswordHash;

    public SeedService(UserRepository userRepository,
                       CropRepository cropRepository,
                       DispatchTeamRepository teamRepository,
                       WorkerRepository workerRepository,
                       OrderRepository orderRepository,
                       TalentRepository talentRepository,
                       BrochureRepository brochureRepository,
                       WeatherStateRepository weatherStateRepository,
                       AttendanceRepository attendanceRepository,
                       PaymentTransactionRepository paymentTransactionRepository,
                       TeamRatingRepository teamRatingRepository,
                       InsuranceRecordRepository insuranceRecordRepository,
                       PasswordHasher passwordHasher,
                       AgriSettings settings) {
        this.userRepository = userRepository;
        this.cropRepository = cropRepository;
        this.teamRepository = teamRepository;
        this.workerRepository = workerRepository;
        this.orderRepository = orderRepository;
        this.talentRepository = talentRepository;
        this.brochureRepository = brochureRepository;
        this.weatherStateRepository = weatherStateRepository;
        this.attendanceRepository = attendanceRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.teamRatingRepository = teamRatingRepository;
        this.insuranceRecordRepository = insuranceRecordRepository;
        this.passwordHasher = passwordHasher;
        this.settings = settings;
    }

    public void seedAll(boolean cleanFirst) {
        if (cleanFirst) {
            teamRatingRepository.deleteAll();
            insuranceRecordRepository.deleteAll();
            paymentTransactionRepository.deleteAll();
            orderRepository.deleteAll();
            workerRepository.deleteAll();
            talentRepository.deleteAll();
            cropRepository.deleteAll();
            teamRepository.deleteAll();
            userRepository.deleteAll();
            brochureRepository.deleteAll();
            weatherStateRepository.deleteAll();
            attendanceRepository.deleteAll();
        }

        seedUsers();
        seedCrops();
        seedTeams();
        seedWorkers();
        seedInsurance();
        seedOrders();
        seedRatings();
        seedTalents();
        seedBrochures();
        seedWeatherStates();
        seedAttendance();
        migrateLegacyData();
        ensureCredentials();
    }

    /** 依 ID 補缺：不存在才新增，既有資料一律保留 */
    private <T, ID> int saveMissing(JpaRepository<T, ID> repo, List<T> items, Function<T, ID> idOf) {
        int added = 0;
        for (T item : items) {
            if (!repo.existsById(idOf.apply(item))) {
                repo.save(item);
                added++;
            }
        }
        return added;
    }

    // =========================================================================
    // 帳號與密碼
    // =========================================================================

    private void seedUsers() {
        boolean demo = settings.isDemoEnabled();
        // 非 demo 模式：示範帳號不給預設密碼（無法登入），需由管理員以 reset-password 設定
        String userPw = demo ? demoHash() : null;
        List<User> users = List.of(
            new User("worker_foreign", userPw, "Nguyễn Văn Hùng (阮文雄)", "worker_foreign", "W001", "外籍外展農工 (Tier 1)"),
            new User("worker_local", userPw, "林小祥 (帶工領班) / 陳建宏 (機耕)", "worker_local", "W026", "本國帶工領班與機耕 (Tier 2/3)"),
            new User("worker_labor", userPw, "張志明 (合作社農業勞務員)", "worker_local", "W030", AuthService.laborRoleDesc()),
            new User("farmer", userPw, "陳建龍 (阿龍班長)", "farmer", "F001", "八德有機蔬菜產銷班負責人"),
            new User("farmer_yilan", userPw, "李春發 (春發伯)", "farmer", "F002", "宜蘭三星青蔥專班負責人"),
            new User("admin_tech", null, "智農科技調度總監", "admin_tech", "ADM01", "科技公司系統調度與數據管理員")
        );
        for (User u : users) {
            if (userRepository.existsById(u.getUsername())) continue;
            if ("admin_tech".equals(u.getRole())) {
                u.setPassword(passwordHasher.hash(initialAdminPassword()));
            }
            userRepository.save(u);
        }
        if (demo) seedRoadshowAccounts();
    }

    /**
     * 路演來賓帳號（僅 demo 模式）：從 gitignore 的 CSV 讀入，帳號不存在才新增，既有帳號（含已改的密碼）一律保留。
     * 格式：username,password,name,role,refId,roleDesc（# 開頭為註解）
     */
    private void seedRoadshowAccounts() {
        String env = System.getenv("AGRI_ROADSHOW_ACCOUNTS");
        Path file = Path.of(env != null && !env.isBlank() ? env : DEFAULT_ROADSHOW_ACCOUNTS);
        if (!Files.isRegularFile(file)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[路演帳號] 無法讀取 {}：{}", file.toAbsolutePath(), e.getMessage());
            return;
        }
        int added = 0;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("﻿")) line = line.substring(1);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(",", 6);
            if (f.length < 6 || f[0].isBlank() || f[1].isBlank() || !ROADSHOW_ROLES.contains(f[3].strip())) {
                log.warn("[路演帳號] 略過格式錯誤的列：{}", f[0]);
                continue;
            }
            String username = f[0].strip();
            if (userRepository.existsById(username)) continue;
            userRepository.save(new User(username, passwordHasher.hash(f[1].strip()), f[2].strip(),
                    f[3].strip(), f[4].strip(), f[5].strip()));
            added++;
        }
        if (added > 0) log.info("[路演帳號] 已從 {} 新增 {} 個帳號", file.toAbsolutePath(), added);
    }

    /** 管理員初始密碼：AGRI_ADMIN_PASSWORD → demo 示範密碼 → 隨機產生（只在 log 顯示一次） */
    private String initialAdminPassword() {
        String env = settings.getAdminPassword();
        if (env != null && !env.isBlank()) return env;
        if (settings.isDemoEnabled()) return DEMO_ADMIN_PASSWORD;
        byte[] rnd = new byte[12];
        new SecureRandom().nextBytes(rnd);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        log.warn("====================================================");
        log.warn("[安全] 未設定 AGRI_ADMIN_PASSWORD，已為 admin_tech 隨機產生初始密碼（僅顯示這一次）：{}", generated);
        log.warn("[安全] 請登入後立即以 POST /api/auth/change-password 變更密碼。");
        log.warn("====================================================");
        return generated;
    }

    private String demoHash() {
        if (demoPasswordHash == null) {
            demoPasswordHash = passwordHasher.hash(DEMO_PASSWORD);
        }
        return demoPasswordHash;
    }

    /**
     * 啟動時把舊版明碼升級為 PBKDF2 雜湊；demo 模式替尚無密碼的農工補上示範密碼
     */
    public void ensureCredentials() {
        boolean demo = settings.isDemoEnabled();
        int upgraded = 0;
        for (User u : userRepository.findAll()) {
            String pw = u.getPassword();
            if (pw != null && !pw.isEmpty() && !PasswordHasher.isHashed(pw)) {
                if (!demo && KNOWN_DEFAULT_PASSWORDS.contains(pw)) {
                    log.warn("[安全] 帳號 {} 仍使用舊版預設密碼，請以 POST /api/auth/reset-password 重設。", u.getUsername());
                }
                u.setPassword(demo && DEMO_PASSWORD.equals(pw) ? demoHash() : passwordHasher.hash(pw));
                userRepository.save(u);
                upgraded++;
            }
        }
        for (Worker w : workerRepository.findAll()) {
            String pw = w.getPasswordHash();
            if (pw != null && !pw.isEmpty() && !PasswordHasher.isHashed(pw)) {
                w.setPasswordHash(passwordHasher.hash(pw));
                workerRepository.save(w);
                upgraded++;
            } else if (demo && (pw == null || pw.isEmpty())) {
                w.setPasswordHash(demoHash());
                workerRepository.save(w);
            }
        }
        if (upgraded > 0) {
            log.info("[安全] 已將 {} 筆舊版明碼密碼升級為 PBKDF2 雜湊。", upgraded);
        }
    }

    /**
     * 既有資料修正（不覆蓋使用者資料，只修正已知錯誤值）
     * - 本國工 arcNumber 佔位字「本國身分證」→ null（避免證號查詢重複）
     * - 舊版基本工資說明文字 28,590 → 現行常數
     * - 早鳥工時包改為 315 元/hr 不打折：未經使用者修改（金額仍為舊種子值）的示範訂單與標準 DM 改為新金額
     */
    private void migrateLegacyData() {
        for (Worker w : workerRepository.findAll()) {
            if ("本國身分證".equals(w.getArcNumber())) {
                w.setArcNumber(null);
                workerRepository.save(w);
            }
            if (w.getBaseSalary() > 0 && w.getBaseSalary() < LaborConstants.MIN_MONTHLY_WAGE
                    && !"tier3_turnkey".equals(w.getTier())) {
                log.warn("[薪資] 農工 {} 底薪 {} 低於現行基本工資 {}，薪資試算將以基本工資計。",
                        w.getId(), w.getBaseSalary(), LaborConstants.MIN_MONTHLY_WAGE);
            }
        }
        for (User u : userRepository.findAll()) {
            if (u.getRoleDesc() != null && u.getRoleDesc().contains("28,590")) {
                u.setRoleDesc("worker_labor".equals(u.getUsername()) ? AuthService.laborRoleDesc()
                        : u.getRoleDesc().replace("28,590", LaborConstants.formatMoney(LaborConstants.MIN_MONTHLY_WAGE)));
                userRepository.save(u);
            }
        }
        migrateEarlyBirdPricing();
    }

    /** 早鳥 9 折 → 315 元/hr 不打折。值：{舊 finalAmount, 新 unitRate, 新 standardAmount, 新 finalAmount, 新 depositAmount} */
    private static final java.util.Map<String, int[]> EARLY_BIRD_SEED_ORDERS = java.util.Map.of(
            "ORD-2026-001", new int[]{26100, 315, 31500, 31500, 6300},
            "ORD-2026-002", new int[]{53040, 315, 61680, 61680, 12096},
            "ORD-2026-005", new int[]{110064, 360, 122160, 122160, 24192},
            "ORD-2026-006", new int[]{126480, 315, 152400, 152400, 30240},
            "ORD-2026-009", new int[]{36246, 315, 42780, 42780, 8316},
            "ORD-2026-010", new int[]{45750, 315, 48450, 48450, 9450},
            "ORD-2026-013", new int[]{45360, 315, 52920, 52920, 10584},
            "ORD-2026-014", new int[]{34560, 315, 40320, 40320, 8064});
    /** 值：{舊 discountPrice, 新 originalPrice = 新 discountPrice, 新 depositAmount} */
    private static final java.util.Map<String, int[]> EARLY_BIRD_SEED_BROCHURES = java.util.Map.of(
            "dm-mango-2026", new int[]{16200, 18900, 3780},
            "dm-greenhouse-2026", new int[]{30240, 37800, 7560},
            "dm-coldchain-2026", new int[]{19800, 25200, 5040});

    private void migrateEarlyBirdPricing() {
        String oldPlanName = "【Tier 1】產季早鳥工時包 (9折保證出工)";
        EARLY_BIRD_SEED_ORDERS.forEach((id, v) -> orderRepository.findById(id).ifPresent(o -> {
            if (!"early_bird".equals(o.getPlan()) || !oldPlanName.equals(o.getPlanName()) || o.getFinalAmount() != v[0]) return;
            o.setPlanName("【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)");
            o.setUnitRate(v[1]);
            o.setStandardAmount(v[2]);
            o.setFinalAmount(v[3]);
            o.setDepositAmount(v[4]);
            orderRepository.save(o);
        }));
        EARLY_BIRD_SEED_BROCHURES.forEach((id, v) -> brochureRepository.findById(id).ifPresent(b -> {
            if (b.getDiscountPrice() != v[0]) return;
            b.setOriginalPrice(v[1]);
            b.setDiscountPrice(v[1]);
            b.setDepositAmount(v[2]);
            if (b.getDescription() != null) {
                b.setDescription(b.getDescription().replace(
                        "產季前 60 天預購享 9 折優惠，預付 20% 定金即可鎖定黃金採收期第一優先出工特權！",
                        "產季前 60 天預購，每小時 315 元不打折；預付 20% 定金即可鎖定產季名額、優先排班，遲到免單、放鳥雙倍賠！"));
            }
            brochureRepository.save(b);
        }));
    }

    private void seedCrops() {
        {
            saveMissing(cropRepository, List.of(
                new Crop("taoyuan_veg", "桃園溫室有機蔬菜 (八德/大溪)", 20, 3, 290, "全年365天溫室採收",
                        "採收、精緻挑菜、田間分級修葉、生鮮小包裝",
                        "八德蔬果預冷冷鏈包裝中心 (室內B棟：蔬果預冷、自動光波秤重、外銷包裝)"),
                new Crop("scallion", "宜蘭三星青蔥 (三星/壯圍)", 24, 3, 300, "6月 ~ 10月",
                        "拔蔥、專用洗蔥機作業、分級束捆、冷藏保鮮預備",
                        "三星地區農會洗蔥預冷處理場 (水洗機操作、蔥管套袋、冷鏈排板)"),
                new Crop("hsinchu_tea", "新竹手採茶/坪林包種茶", 32, 4, 320, "4月 ~ 11月",
                        "一心二葉精緻手採、輕柔採摘、防曬通風保鮮",
                        "峨眉茶葉冷鏈溫控發酵庫 (室內分級挑枝、溫濕度控制包裝)"),
                new Crop("xinpu_citrus", "新竹新埔柑橘/柿餅", 26, 3, 300, "10月 ~ 1月",
                        "產季採收、修枝防果刺、削皮整枝、分級裝箱",
                        "新埔農會柑橘蔬果低溫集貨場 (清洗預冷、自動選果分級包裝)"),
                new Crop("mango", "愛文芒果 (屏東枋山/台南玉井)", 28, 4, 300, "4月 ~ 7月",
                        "外銷級採收 (留蒂 0.5cm 護果粉)、疏果、防菌套袋",
                        "屏南冷鏈預冷分級場 (清洗預冷、重量自動分級、外銷裝箱)"),
                new Crop("onion", "洋蔥 (屏東恆春/高樹)", 32, 4, 290, "1月 ~ 4月",
                        "拔蔥、剪根剪葉、田間裝袋 (每袋 20kg)、搬運上埂",
                        "恆春恆農洋蔥低溫冷藏通風庫 (分級篩選、冷藏排板)"),
                new Crop("pepper", "設施彩椒/小黃瓜 (彰化/雲林)", 20, 3, 310, "全年設施溫室",
                        "整枝修芽、溫室精準分批採摘、防蟲網維護",
                        "西螺蔬果集貨冷藏中心 (生鮮小包裝、貼標封口)"),
                new Crop("strawberry", "苗栗大湖香水草莓", 25, 3, 310, "12月 ~ 4月",
                        "高架草莓精密疏花疏果、紅熟採收、海綿襯墊防撞裝盒",
                        "大湖蔬果低溫集貨中心 (快速降溫預冷、生鮮冷鏈分級包裝)"),
                new Crop("mushroom", "台中新社太空包香菇", 22, 3, 295, "全年環控溫室",
                        "菇傘熟成採收、菌包清理轉化、溫濕度監測",
                        "新社香菇低溫乾燥分級中心 (低溫烘乾排板、光波選別包裝)"),
                new Crop("melon", "宜蘭壯圍網紋洋香瓜", 30, 4, 330, "5月 ~ 8月",
                        "溫室一株一果精緻管理、掛果防磨套袋、成熟度測糖採收",
                        "壯圍農會冷藏包裝作業場 (自動甜度光波檢測、精品禮盒排裝)"),
                // 以下為農場主預約表單 (farmer.html) 提供之作物；工時與費率為示範參考值，正式營運前需依實際產區校正
                new Crop("pineapple", "金鑽鳳梨 (屏東高樹/高雄大樹)", 26, 3, 300, "3月 ~ 8月",
                        "產季採收、去冠芽整理、田間分級裝箱、搬運上車",
                        "產地農會集貨場 (分級選果、裝箱封箱)"),
                new Crop("waxapple", "黑珍珠蓮霧 (屏東佳冬/林邊)", 30, 4, 300, "12月 ~ 4月",
                        "修剪疏果、套袋、採收分級",
                        "產地農會集貨場 (選果分級、防撞包裝)"),
                new Crop("tea", "高山茶 (阿里山/杉林溪)", 34, 4, 320, "4月 ~ 11月",
                        "一心二葉人工採茶、茶菁運送、防曬保鮮",
                        "合作茶廠室內作業 (揀枝、分級、包裝)"),
                new Crop("pomelo", "麻豆文旦 (台南麻豆/花蓮)", 24, 3, 300, "8月 ~ 9月",
                        "產季搶收、果面清潔、分級裝箱",
                        "產地農會集貨場 (選果分級、禮盒裝箱)"),
                new Crop("rice", "水稻/雜糧 (整地輪作)", 12, 2, 290, "2月 ~ 3月、7月 ~ 8月",
                        "整地、育苗搬運、田埂整理、輪作雜糧播種",
                        "育苗中心室內作業 (秧苗盤整理、資材搬運)"),
                new Crop("pear", "高接梨 (台中東勢/苗栗卓蘭)", 32, 3, 300, "7月 ~ 9月",
                        "嫁接、高空修剪、套袋、採收分級",
                        "產地農會集貨場 (選果分級、禮盒裝箱)")
            ), Crop::getId);
        }
    }

    private void seedTeams() {
        {
            saveMissing(teamRepository, List.of(
                new DispatchTeam("team_a", "工班 A 組 (桃園溫室組)", 7, "阮文雄 (Nguyễn Văn Hùng)", "0988-111-222",
                        "智農 1 號專車 (RBF-8899)", "張大哥 (0912-111-333)",
                        "🌾 八德有機蔬菜專銷班 溫室第 3 區", "有機葉菜採收、修葉分級、生鮮小包裝",
                        "🏢 八德蔬果預冷冷鏈包裝中心 (室內B棟)", "蔬果預冷降溫、自動光波秤重、外銷包裝貼標", "active_field"),
                new DispatchTeam("team_b", "工班 B 組 (跨區專線組)", 8, "阿迪 (Agus Santoso)", "0988-222-333",
                        "智農 2 號專車 (跨區專線 / RBF-9988)", "李大哥 (0912-222-444)",
                        "🌾 宜蘭三星青蔥專區 (三星鄉農會契作田)", "洗蔥、拔蔥、分級束捆、冷藏保鮮預備",
                        "🏢 三星地區農會洗蔥預冷處理場", "水洗機操作、蔥管套袋、冷鏈排板", "active_field"),
                new DispatchTeam("team_c", "工班 C 組 (機耕無人機隊)", 5, "陳建宏 (資深農機技師)", "0988-333-444",
                        "智農 3 號工程車 (配大疆 T50 植保機)", "陳建宏",
                        "🌾 新竹峨眉手採茶園 / 桃園溫室", "無人機精準代噴 + 曳引機整地翻土作業",
                        "🏢 智慧農業資材維護所", "無人機機隊拆解保養、噴嘴超音波清洗、調配資材準備", "active_field"),
                new DispatchTeam("team_d", "工班 D 組 (冷鏈常態組)", 6, "范氏紅 (Phạm Thị Hồng)", "0988-444-555",
                        "智農專車固定專線", "王大哥 (0912-555-666)",
                        "🏢 八德蔬果預冷冷鏈包裝中心 (室內B棟)", "蔬果預冷降溫、外銷精密秤重分級、冷鏈櫃裝櫃",
                        "🏢 八德蔬果預冷冷鏈包裝中心 (室內B棟)", "蔬果預冷降溫、外銷精密秤重分級、冷鏈櫃裝櫃", "hedged_indoor")
            ), DispatchTeam::getId);
        }
    }

    private void seedWorkers() {
        {
            // 底薪欄位 "MIN" = 現行基本工資常數；獎勵金 "BONUS" = 農業部就業獎勵金常數
            // 本國工無居留證，arcNumber 以空字串表示並存為 null
            String[][] raw = {
                {"W001", "Nguyễn Văn Hùng", "阮文雄", "越南", "team_a", "true", "tier1_foreign", "MIN", "BONUS", "1200", "AD88123451", "C9823101", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 採收與修葉分級", "dispatched_field", "0988-111-222"},
                {"W002", "Trần Văn Bình", "陳文平", "越南", "team_a", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD88123452", "C9823102", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 溫室採收裝箱", "dispatched_field", "0988-111-223"},
                {"W003", "Lê Thị Mai", "黎氏梅", "越南", "team_a", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD88123453", "C9823103", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 生鮮小包裝貼標", "dispatched_field", "0988-111-224"},
                {"W004", "Phạm Văn Đức", "范文德", "越南", "team_a", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD88123454", "C9823104", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 搬運上埂裝車", "dispatched_field", "0988-111-225"},
                {"W005", "Hoàng Minh Tuấn", "黃明俊", "越南", "team_a", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD88123455", "C9823105", "2027-08-31", "已納保 (勞/健/職災)", "八德有機蔬菜專區 溫室翻土整畦", "dispatched_field", "0988-111-226"},
                {"W006", "Agus Santoso", "阿迪", "印尼", "team_b", "true", "tier1_foreign", "MIN", "BONUS", "1200", "AD77234561", "X8812301", "2027-06-30", "已納保 (勞/健/職災)", "枋山愛文芒果園 套袋疏果與外銷採收", "dispatched_field", "0988-222-333"},
                {"W007", "Budi Setiawan", "布迪", "印尼", "team_b", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD77234562", "X8812302", "2027-06-30", "已納保 (勞/健/職災)", "枋山愛文芒果園 剪枝留蒂與果品初選", "dispatched_field", "0988-222-334"},
                {"W008", "Dewi Lestari", "蒂薇", "印尼", "team_b", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD77234563", "X8812303", "2027-06-30", "已納保 (勞/健/職災)", "枋山愛文芒果園 防菌果袋封口", "dispatched_field", "0988-222-335"},
                {"W009", "Eko Prasetyo", "艾科", "印尼", "team_b", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD77234564", "X8812304", "2027-06-30", "已納保 (勞/健/職災)", "宜蘭三星青蔥田 洗蔥與分級束捆", "dispatched_field", "0988-222-336"},
                {"W010", "Fajar Hidayat", "法賈", "印尼", "team_b", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD77234565", "X8812305", "2027-06-30", "已納保 (勞/健/職災)", "宜蘭三星青蔥田 拔蔥與田間裝簍", "dispatched_field", "0988-222-337"},
                {"W011", "Gita Pratama", "吉塔", "印尼", "team_b", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD77234566", "X8812306", "2027-06-30", "已納保 (勞/健/職災)", "宜蘭三星青蔥田 水洗機操作與套袋", "dispatched_field", "0988-222-338"},
                {"W012", "陳建宏", "陳建宏", "本國", "team_c", "true", "tier3_turnkey", "45000", "BONUS", "3000", "", "312456789", "永久有效", "已納保 (勞保/職災/雇主責任險)", "新竹峨眉茶園 植保機無人機精準代噴", "dispatched_field", "0988-333-444"},
                {"W013", "林志強", "林志強", "本國", "team_c", "false", "tier3_turnkey", "42000", "BONUS", "3000", "", "312456790", "永久有效", "已納保 (勞保/職災/雇主責任險)", "大溪溫室群 大疆T50巡檢與代噴", "dispatched_field", "0988-333-445"},
                {"W014", "Đặng Văn Lâm", "鄧文林", "越南", "team_c", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD88123456", "C9823106", "2027-09-15", "已納保 (勞/健/職災)", "資材維護所 噴嘴超音波清洗與混藥", "dispatched_field", "0988-333-446"},
                {"W015", "Hadi Wijaya", "哈迪", "印尼", "team_c", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD77234567", "X8812307", "2027-09-15", "已納保 (勞/健/職災)", "機耕隊 曳引機整地翻土隨車輔助", "dispatched_field", "0988-333-447"},
                {"W016", "Phạm Thị Hồng", "范氏紅", "越南", "team_d", "true", "tier1_foreign", "MIN", "BONUS", "1200", "AD88123457", "C9823107", "2027-11-20", "已納保 (勞/健/職災)", "八德蔬果冷鏈包裝中心 外銷精密秤重分級", "hedged_indoor", "0988-444-555"},
                {"W017", "Vũ Thị Hương", "武氏香", "越南", "team_d", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD88123458", "C9823108", "2027-11-20", "已納保 (勞/健/職災)", "八德蔬果冷鏈中心 預冷降溫與套袋", "hedged_indoor", "0988-444-556"},
                {"W018", "Siti Rahma", "西蒂", "印尼", "team_d", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD77234568", "X8812308", "2027-11-20", "已納保 (勞/健/職災)", "八德蔬果冷鏈中心 外銷紙箱組裝貼標", "hedged_indoor", "0988-444-557"},
                {"W019", "Nurul Aini", "努魯", "印尼", "team_d", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD77234569", "X8812309", "2027-11-20", "已納保 (勞/健/職災)", "八德蔬果冷鏈中心 光波選果機操作", "hedged_indoor", "0988-444-558"},
                {"W020", "Somchai Prasert", "宋猜", "泰國", "team_d", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD66345671", "T5512301", "2027-12-10", "已納保 (勞/健/職災)", "冷鏈常態組 低溫冷藏庫棧板堆疊", "hedged_indoor", "0988-444-559"},
                {"W021", "黃慶龍", "黃慶龍", "本國", "team_a", "false", "tier2_local_flex", "33000", "BONUS", "2000", "", "312456791", "永久有效", "已納保 (勞保/職災險)", "桃園八德 兼職採收與運搬支援", "standby", "0911-333-222"},
                {"W022", "李麗珠", "李麗珠", "本國", "team_a", "false", "tier2_local_flex", "31000", "BONUS", "1800", "", "312456792", "永久有效", "已納保 (勞保/職災險)", "大溪彩椒園 彈性兼職整枝摘果", "standby", "0922-444-333"},
                {"W023", "Maria Santos", "瑪麗亞", "菲律賓", "team_b", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD55456781", "P4412301", "2028-01-15", "已納保 (勞/健/職災)", "枋山愛文芒果產區 外銷果品裝箱", "dispatched_field", "0988-222-339"},
                {"W024", "Korn Somsak", "阿頌", "泰國", "team_b", "false", "tier1_foreign", "MIN", "BONUS", "1200", "AD66345672", "T5512302", "2028-01-15", "已納保 (勞/健/職災)", "宜蘭三星青蔥專區 清晨拔蔥出勤", "dispatched_field", "0988-222-340"},
                {"W025", "張天成", "張天成", "本國", "team_c", "false", "tier3_turnkey", "43500", "BONUS", "3000", "", "312456793", "永久有效", "已納保 (勞保/職災/雇主責任險)", "水稻與雜糧專區 80馬力曳引機深耕整地", "dispatched_field", "0933-555-666"},
                {"W026", "林小祥", "林小祥", "本國", "team_b", "true", "tier2_local_flex", "42000", "8000", "2800", "", "312456794", "永久有效", "已納保 (勞保/職災/雇主責任險)", "外展帶隊小工頭 帶領6名外勞於枋山芒果園作業與品質驗收", "dispatched_field", "0918-668-999"},
                {"W030", "張志明", "張志明", "本國", "team_a", "false", "tier2_local_flex", "MIN", "BONUS", "2400", "", "312456795", "永久有效", "已納保 (勞保/健保/6%勞退)", "合作社在籍農業勞務員 八德有機蔬菜專區田間採收與出勤", "dispatched_field", "0975-123-456"}
            };

            for (String[] rw : raw) {
                if (workerRepository.existsById(rw[0])) continue; // 依 ID 補缺，不覆蓋既有資料
                boolean isLeader = Boolean.parseBoolean(rw[5]);
                int baseSalary = "MIN".equals(rw[7]) ? LaborConstants.MIN_MONTHLY_WAGE : Integer.parseInt(rw[7]);
                int agriBonus = "BONUS".equals(rw[8]) ? LaborConstants.AGRI_EMPLOYMENT_BONUS : Integer.parseInt(rw[8]);
                int transport = Integer.parseInt(rw[9]);
                String arc = rw[10].isEmpty() ? null : rw[10];
                workerRepository.save(new Worker(rw[0], rw[1], rw[2], rw[3], rw[4], isLeader, rw[6],
                        baseSalary, agriBonus, transport, arc, rw[11], rw[12], rw[13], rw[14], rw[15], rw[16]));
            }
        }
    }

    private void seedOrders() {
        {
            List<Order> orders = List.of(
                createOrder("ORD-2026-001", "陳建龍 (阿龍班長)", "0912-345-678", "桃園市八德區興豐路有機蔬菜專區",
                        "taoyuan_veg", "桃園溫室有機蔬菜 (八德)", 5.0, "early_bird", "【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)",
                        "harvest_grading", "【採收分級】田間採收與外銷分級",
                        "廖國安 (八德三號棚領班)", "0932-111-222", "進場請先至 3 號棚工具室報到，由廖領班分派菜畦",
                        "core_35km", 0, 100, 3, 315, 31500, 31500, 6300, true, "confirmed", "team_a", "2026-09-18 08:30:00"),
                createOrder("ORD-2026-002", "林友財 (三星青蔥契作組長)", "0921-888-777", "宜蘭縣三星鄉大隱村青蔥契作田",
                        "scallion", "宜蘭三星青蔥 (三星/壯圍)", 8.0, "early_bird", "【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)",
                        "harvest_grading", "【採收分級】田間採收與外銷分級",
                        "林友財 (本人帶隊)", "0921-888-777", "水洗機已備妥，請直接至水洗場分組出工",
                        "cross_region", 1200, 192, 4, 315, 61680, 61680, 12096, true, "in_progress", "team_b", "2026-09-19 09:15:00"),
                createOrder("ORD-2026-003", "張金泉 (峨眉東方美人茶園)", "0932-111-999", "新竹縣峨眉鄉石井村茶園第2區",
                        "hsinchu_tea", "新竹手採茶/坪林包種茶", 3.5, "turnkey", "【Tier 3】專業成果統包代耕 (驗收付款)",
                        "drone_pest_control", "【科技植保】無人機精準噴藥施肥",
                        "張春輝 (茶園現場組長)", "0935-222-333", "無人機作業前需向張組長確認避風航線與高壓電線位置",
                        "core_35km", 0, 112, 4, 320, 35840, 34048, 10214, true, "in_progress", "team_c", "2026-09-20 07:45:00"),
                createOrder("ORD-2026-004", "黃世勳 (大溪溫室彩椒產銷園)", "0918-666-333", "桃園市大溪區康莊路三段溫室群",
                        "pepper", "設施彩椒/小黃瓜 (彰化/雲林)", 4.0, "hourly_flex", "【Tier 2】在地彈性兼職包 (隨叫隨派)",
                        "pruning_bagging", "【田間管理】修枝疏果與引蔓套袋",
                        "黃世勳 (本人帶隊)", "0918-666-333", "彩椒棚內已拉好引蔓線，請帶剪定鋏",
                        "core_35km", 0, 80, 3, 310, 24800, 24800, 0, true, "completed", "team_a", "2026-09-15 10:00:00"),
                createOrder("ORD-2026-005", "枋山愛文芒果產銷第7班", "0935-777-555", "屏東縣枋山鄉善餘村芒果專區",
                        "mango", "愛文芒果 (屏東枋山/台南玉井)", 12.0, "early_bird", "【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)",
                        "typhoon_emergency", "【緊急救火】防颱搶收 24H 馳援",
                        "柯志明 (搶收應變指揮)", "0920-555-666", "🚨 颱風警報搶收，24H分兩班，向柯指揮官領取採收箱",
                        "cross_region", 1200, 336, 6, 360, 122160, 122160, 24192, false, "pending", "team_b", "2026-09-21 14:10:00"),
                createOrder("ORD-2026-006", "恆春鎮農會契作洋蔥大聯盟", "0910-222-333", "屏東縣恆春鎮德和里洋蔥專業區",
                        "onion", "洋蔥 (屏東恆春/高樹)", 15.0, "early_bird", "【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)",
                        "tillage_planting", "【整地種植】曳引機深耕與定植",
                        "陳得勝 (洋蔥班工頭)", "0915-444-555", "曳引機進場請走東側產業道路，注意灌溉溝渠",
                        "cross_region", 1200, 480, 6, 315, 152400, 152400, 30240, true, "completed", "team_c", "2026-09-12 08:00:00"),
                createOrder("ORD-2026-007", "彭宏基 (新埔照門柑橘園)", "0928-333-888", "新竹縣新埔鎮照門里果園",
                        "xinpu_citrus", "新竹新埔柑橘/柿餅", 6.0, "hourly_flex", "【Tier 2】在地彈性兼職包 (隨叫隨派)",
                        "pruning_bagging", "【田間管理】修枝疏果與引蔓套袋",
                        "彭宏基 (本人帶隊)", "0928-333-888", "柑橘園果袋已置於工寮，修剪枝條集中放置",
                        "core_35km", 0, 156, 3, 310, 48360, 48360, 0, true, "confirmed", "team_a", "2026-09-22 11:20:00"),
                createOrder("ORD-2026-008", "大湖滿意香水草莓園", "0919-444-111", "苗栗縣大湖鄉富興村水尾坪",
                        "strawberry", "苗栗大湖香水草莓", 4.5, "hourly_flex", "【Tier 2】在地彈性兼職包 (隨叫隨派)",
                        "harvest_grading", "【採收分級】田間採收與外銷分級",
                        "張金福 (草莓棚領班)", "0919-666-777", "草莓採收需輕拿輕放，九分熟紅果優先挑選",
                        "cross_region", 1200, 113, 3, 310, 36230, 36230, 0, false, "pending", "team_a", "2026-09-22 16:40:00"),
                createOrder("ORD-2026-009", "新社香菇智慧產銷班", "0937-555-888", "台中市新社區協成里香菇溫室",
                        "mushroom", "台中新社太空包香菇", 6.0, "early_bird", "【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)",
                        "cold_chain_pack", "【冷鏈包裝】室內預冷與生鮮截切",
                        "李添木 (溫控班長)", "0937-111-333", "進入預冷庫請著保暖外套，先做截切後入庫",
                        "cross_region", 1200, 132, 3, 315, 42780, 42780, 8316, true, "confirmed", "team_d", "2026-09-20 13:15:00"),
                createOrder("ORD-2026-010", "壯圍網紋洋香瓜示範園", "0922-999-000", "宜蘭縣壯圍鄉新南村瓜棚",
                        "melon", "宜蘭壯圍網紋洋香瓜", 5.0, "early_bird", "【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)",
                        "pruning_bagging", "【田間管理】修枝疏果與引蔓套袋",
                        "吳政宏 (瓜棚領工)", "0922-333-444", "網紋瓜疏果一株留一果，請依標記修剪",
                        "cross_region", 1200, 150, 4, 315, 48450, 48450, 9450, false, "cancelled", "team_b", "2026-09-17 15:30:00"),
                createOrder("ORD-2026-011", "蘆竹水耕智慧農場", "0911-333-222", "桃園市蘆竹區南山路溫室二廠",
                        "taoyuan_veg", "桃園溫室有機蔬菜 (八德/大溪)", 3.0, "hourly_flex", "【Tier 2】在地彈性兼職包 (隨叫隨派)",
                        "cold_chain_pack", "【冷鏈包裝】室內預冷與生鮮截切",
                        "林佳蓉 (冷鏈場主管)", "0911-888-999", "八德二廠冷鏈包裝線，全日室內空調作業",
                        "core_35km", 0, 60, 2, 310, 18600, 18600, 0, true, "in_progress", "team_d", "2026-09-21 08:20:00"),
                createOrder("ORD-2026-012", "彰化溪州蔬菜契作合作社", "0938-111-666", "彰化縣溪州鄉中央路蔬菜專區",
                        "pepper", "設施彩椒/小黃瓜 (彰化/雲林)", 7.5, "turnkey", "【Tier 3】專業成果統包代耕 (驗收付款)",
                        "tillage_planting", "【整地種植】曳引機深耕與定植",
                        "許世芳 (溪州機耕隊長)", "0938-777-888", "大型曳引機翻土作業，請依地籍圖作畦",
                        "cross_region", 1200, 150, 4, 310, 47700, 45375, 13253, true, "completed", "team_c", "2026-09-10 09:00:00"),
                // 農場主工作台 (farmer.html) 展示的兩筆訂單；金額與後端試算 /api/orders/calculate 一致
                createOrder("ORD-2026-013", "陳建龍 (阿龍班長)", "0912-345-678", "枋山愛文芒果合作園區 2 號地",
                        "mango", "愛文芒果 (屏東枋山/台南玉井)", 6.0, "early_bird", "【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)",
                        "harvest_grading", "【採收分級】田間採收與外銷分級",
                        "林小龍 (副班長)", "0921-999-888", "早上 06:45 枋山 2 號集貨場集合",
                        "core_35km", 0, 168, 6, 315, 52920, 52920, 10584, true, "in_progress", "team_b", "2026-09-22 09:00:00"),
                createOrder("ORD-2026-014", "陳建龍 (阿龍班長)", "0912-345-678", "苗栗卓蘭果園產區",
                        "pear", "高接梨 (台中東勢/苗栗卓蘭)", 4.0, "early_bird", "【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)",
                        "pruning_bagging", "【田間管理】修枝疏果與引蔓套袋",
                        "陳建龍 (農場主本人帶隊)", "0912-345-678", "農場主本人親自現場帶隊指揮調度",
                        "core_35km", 0, 128, 5, 315, 40320, 40320, 8064, false, "pending", "待分派", "2026-09-23 10:00:00")
            );
            // 示範農場主 F001（陳建龍）的訂單
            orders.get(0).setFarmerId("F001");
            orders.stream().filter(o -> o.getId().equals("ORD-2026-013") || o.getId().equals("ORD-2026-014"))
                    .forEach(o -> o.setFarmerId("F001"));
            saveMissing(orderRepository, orders, Order::getId);
        }
    }

    /** 示範工人的加保紀錄：每位工人依身分加保應加險種，投保薪資取月薪與基本工資較高者 */
    private void seedInsurance() {
        java.time.LocalDate enrollDate = java.time.LocalDate.of(2026, 3, 1);
        java.time.LocalDateTime createdAt = enrollDate.atTime(9, 0);
        List<InsuranceRecord> records = new java.util.ArrayList<>();
        for (Worker w : workerRepository.findAll()) {
            int salary = Math.max(w.getBaseSalary(), LaborConstants.MIN_MONTHLY_WAGE);
            for (String type : InsuranceService.requiredTypes(w)) {
                InsuranceRecord r = new InsuranceRecord();
                r.setId("INS-" + w.getId() + "-" + type + "-" + enrollDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
                r.setWorkerId(w.getId());
                r.setType(type);
                r.setInsuredSalary(salary);
                r.setEnrollDate(enrollDate);
                r.setFilingRef("示範資料");
                r.setCreatedAt(createdAt);
                r.setUpdatedAt(createdAt);
                records.add(r);
            }
        }
        saveMissing(insuranceRecordRepository, records, InsuranceRecord::getId);
    }

    /** 已出工／完工訂單的歷史評價（ORD-2026-013 留空，供農場主工作台現場示範評價） */
    private void seedRatings() {
        Object[][] rows = {
                {"ORD-2026-002", "team_b", 5, "準時到場,效率高,溝通順暢", "青蔥採收進度超前，班長每天回報很清楚。", "2026-09-21T18:00"},
                {"ORD-2026-004", "team_a", 4, "做工細心,遵守安全規範", "彩椒引蔓修剪仔細，第一天稍慢但後來跟上。", "2026-09-18T17:30"},
                {"ORD-2026-006", "team_c", 5, "效率高,遵守安全規範", "曳引機深耕準時完成，田區收拾乾淨。", "2026-09-14T16:00"},
                {"ORD-2026-011", "team_d", 4, "準時到場,做工細心", "冷鏈截切品質穩定。", "2026-09-22T19:10"},
                {"ORD-2026-012", "team_c", 4, "效率高,需加強訓練", "整地速度快，作畦間距有兩區需重做。", "2026-09-12T15:20"}
        };
        List<TeamRating> ratings = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            TeamRating r = new TeamRating();
            r.setId("RAT-" + row[0]);
            r.setOrderId((String) row[0]);
            r.setTeamId((String) row[1]);
            r.setScore((Integer) row[2]);
            r.setTags((String) row[3]);
            r.setComment((String) row[4]);
            java.time.LocalDateTime at = java.time.LocalDateTime.parse((String) row[5]);
            r.setCreatedAt(at);
            r.setUpdatedAt(at);
            ratings.add(r);
        }
        saveMissing(teamRatingRepository, ratings, TeamRating::getId);
    }

    private Order createOrder(String id, String farmerName, String phone, String farmLocation,
                              String cropId, String cropName, double area, String plan, String planName,
                              String dispatchCategory, String categoryName,
                              String foremanName, String foremanPhone, String foremanNotes,
                              String transportZone, int surcharge, int totalHours, int suggestedWorkers,
                              int unitRate, int standardAmount, int finalAmount, int depositAmount,
                              boolean depositPaid, String status, String assignedTeamId, String createdAt) {
        Order o = new Order();
        o.setId(id);
        o.setFarmerName(farmerName);
        o.setPhone(phone);
        o.setFarmLocation(farmLocation);
        o.setCropId(cropId);
        o.setCropName(cropName);
        o.setArea(area);
        o.setPlan(plan);
        o.setPlanName(planName);
        o.setDispatchCategory(dispatchCategory);
        o.setCategoryName(categoryName);
        o.setForemanName(foremanName);
        o.setForemanPhone(foremanPhone);
        o.setForemanNotes(foremanNotes);
        o.setTransportZone(transportZone);
        o.setTransportSurcharge(surcharge);
        o.setTotalHours(totalHours);
        o.setSuggestedWorkers(suggestedWorkers);
        o.setUnitRate(unitRate);
        o.setStandardAmount(standardAmount);
        o.setFinalAmount(finalAmount);
        o.setDepositAmount(depositAmount);
        o.setDepositPaid(depositPaid);
        o.setStatus(status);
        o.setAssignedTeamId(assignedTeamId);
        o.setCreatedAt(createdAt);
        return o;
    }

    private void seedTalents() {
        {
            saveMissing(talentRepository, List.of(
                new Talent("TAL-001", "陳文義", "local_driver", "本國大型農機/曳引機駕駛員 (Tier 3)",
                        "0933-222-111", "具備 80 馬力曳引機 5 年耕作經驗、北部蔬菜溫室整地熟練", true, "2026-09-18 14:20:00"),
                new Talent("TAL-002", "劉家銘", "drone_pilot", "本國專業植保機/無人機手 (Tier 3)",
                        "0925-666-888", "持有民航局無人機農藥代噴雙證照、熟悉大疆 T50 系統與航線規劃", true, "2026-09-19 11:30:00"),
                new Talent("TAL-003", "Trần Thị Lan (陳氏蘭)", "transfer_worker", "合法期滿中途轉出外展移工 (Tier 1)",
                        "0981-234-567", "在台三年蔬果採收與溫室管理經驗、國語溝通流利、工作態度認真", true, "2026-09-20 09:10:00"),
                new Talent("TAL-004", "郭寶蓮", "local_flex", "本國二度就業/彈性兼職農工 (Tier 2)",
                        "0917-888-222", "大溪在地農民、熟悉溫室瓜果疏果修枝與包裝作業、隨叫隨派", true, "2026-09-21 15:40:00"),
                new Talent("TAL-005", "張建國", "local_driver", "本國大型農機/曳引機駕駛員 (Tier 3)",
                        "0936-777-333", "熟悉乘坐式插秧機與履帶式迴轉機、具備南部洋蔥田整地經驗", true, "2026-09-21 16:50:00"),
                new Talent("TAL-006", "Bambang Wijaya (班邦)", "transfer_worker", "合法期滿中途轉出外展移工 (Tier 1)",
                        "0975-333-111", "印尼籍農工、熟悉茶園採收修剪與噴霧機具操作、體力充沛", true, "2026-09-22 10:00:00"),
                new Talent("TAL-007", "許秋香", "local_flex", "本國二度就業/彈性兼職農工 (Tier 2)",
                        "0929-111-444", "八德有機產銷班包裝線熟手、光波秤重與外銷貼標作業專精", true, "2026-09-22 13:25:00"),
                new Talent("TAL-008", "王冠宇", "drone_pilot", "本國專業植保機/無人機手 (Tier 3)",
                        "0963-888-999", "具備大面積果園與茶園無人機低空精準代噴 3 年經驗", true, "2026-09-22 17:00:00")
            ), Talent::getId);
        }
    }

    private void seedBrochures() {
        {
            Brochure dm1 = new Brochure(
                "dm-mango-2026",
                "【屏東枋山外銷愛文芒果】產季早鳥保證出工 60 工時包",
                "告別產季搶工被放鳥！合法外展工班 ╳ 產季保證出工 ╳ 梅雨冷鏈避險",
                "採收早鳥",
                "愛文芒果",
                "屏東枋山、台南玉井",
                "外銷供果園、全聯契作戶、外銷貿易商契作園",
                "★ 產銷班長指名推薦款",
                "🥭",
                "emerald",
                "https://images.unsplash.com/photo-1553279768-865429fa0078?w=800&auto=format&fit=crop&q=80",
                18900,
                18900,
                20,
                3780,
                60,
                4,
                2,
                "內含 60 個標準外展技術工時（約 4 位熟練農工出勤 2 天）。產季前 60 天預購，每小時 315 元不打折；預付 20% 定金即可鎖定產季名額、優先排班，遲到免單、放鳥雙倍賠！",
                "[{\"icon\":\"🛡️\",\"title\":\"保證 1：保證出工，違約雙倍賠償\",\"desc\":\"合約簽訂後，約定出工日清晨 07:00 前專車準時送抵田門口。因平台失誤導致延遲，當日工資全免並加倍賠償農損！\"},{\"icon\":\"✂️\",\"title\":\"保證 2：外銷採收 SOP，保護果粉\",\"desc\":\"全程配戴細絨棉手套，嚴禁手指摩擦果粉。蒂頭精準保留 0.5～1.0 公分，輕採輕放，特級品良率高達 95% 以上！\"},{\"icon\":\"🌧️\",\"title\":\"保證 3：下雨轉冷鏈，果農零停工損失\",\"desc\":\"遇暴雨田區泥濘，專車 15 分鐘轉接至屏南冷鏈集貨場做外銷分級套袋。果農免付停工底薪，風雨無阻全勤營運！\"},{\"icon\":\"🧾\",\"title\":\"保證 4：合法發票與勞保名冊，安心報帳\",\"desc\":\"完工即開立合作社統一發票並附上全員職災保險名冊，全聯、好市多與國稅局稽核 100% 合規，經營高枕無憂！\"}]",
                "[{\"item\":\"法律合規性\",\"illegal\":\"違法非法黑工，被查獲罰 15～75 萬元\",\"legal\":\"依外展農務制度申請中，依法足額納保！\"},{\"item\":\"出工確定性\",\"illegal\":\"口頭約定，隨時被隔壁大戶加價 300 截工放鳥\",\"legal\":\"簽署正式契約，白紙黑字保證出工，違約雙倍賠償！\"},{\"item\":\"採收手路技術\",\"illegal\":\"烏合之眾，碰掉外銷果粉、剪傷蒂頭變次級品\",\"legal\":\"受過外銷日韓採收 SOP 訓練，蒂頭留 0.5cm，果粉完好！\"},{\"item\":\"下雨天候停工\",\"illegal\":\"田間泥濘無法下田，果農白賠錢，工人沒收入\",\"legal\":\"降雨 15 分鐘專車轉派屏南冷鏈預冷場包裝，零損失！\"},{\"item\":\"發票與稽核\",\"illegal\":\"只能開手寫白單，全聯、好市多無法報帳抵稅\",\"legal\":\"開立合法合作社統一發票，全聯通路稽核 100% 過關！\"}]",
                "陳建龍（阿龍班長）",
                "枋山愛文芒果產銷班第 7 班 班長",
                "【預期效益示意，非實際客戶見證】「去年被工頭放鳥，爛了快 30 萬芒果，我差點跟老婆翻臉。今年我第一個跟智農派工簽早鳥合約，預付定金。5 月 20 號那天早上 6 點 50 分，4 個越南年輕人準時站在我田門口，剪蒂頭比我自己剪的還幼路！下雨天還能調去冷鏈場包裝。明年產季，我們全班都要一起簽早鳥包！」",
                "08-876-1234 / 0912-345-678",
                "屏東縣枋山鄉加祿路 128 號 (加祿保安宮旁)",
                "active",
                "2026-09-01"
            );

            Brochure dm2 = new Brochure(
                "dm-greenhouse-2026",
                "【桃園/雲林設施有機溫室】全年常態巡檢與蔬果採收包裝 120 工時包",
                "水耕與有機溫室首選！365天穩定固定工班 ╳ 定植採收分級一貫化 ╳ 供應營養午餐零斷線",
                "設施溫室",
                "有機溫室蔬果",
                "桃園八德、大溪、雲林西螺",
                "大台北有機蔬菜供銷班、全聯有機水耕專區、國中小營養午餐供應商",
                "★ 全年常態合作滿意度 99.8%",
                "🥬",
                "lime",
                "https://images.unsplash.com/photo-1585320806297-9794b3e4eeae?w=800&auto=format&fit=crop&q=80",
                37800,
                37800,
                20,
                7560,
                120,
                6,
                4,
                "專為溫室有機蔬菜農場量身打造的月約工時包！提供受過有機驗證衛生訓練之常態班底，含溫室穴盤定植、除草整枝、精緻採收與室內截切預冷包裝。",
                "[{\"icon\":\"🌱\",\"title\":\"保證 1：固定班底，免天天重新培訓\",\"desc\":\"指派專屬溫室常駐工班，熟悉農場作業手路，降低作物折損率至 1% 以下！\"},{\"icon\":\"🧤\",\"title\":\"保證 2：符合有機農產品驗證衛生標準\",\"desc\":\"配戴專用防護鞋套、帽子與衛生手套，作業前後嚴格工具消毒，100% 符規。\"},{\"icon\":\"📦\",\"title\":\"保證 3：採後室內預冷截切分級無縫接軌\",\"desc\":\"採收完畢立即轉入 15°C 預冷室作業，蔬果保鮮期大幅延長 48 小時！\"},{\"icon\":\"🧾\",\"title\":\"保證 4：提供合法月結發票與企業扣抵憑證\",\"desc\":\"每月定期彙整工時簽核單，開立營業稅進項憑證，直通大型通路會計系統。\"}]",
                "[{\"item\":\"人員穩定度\",\"illegal\":\"臨時找散工，每天換新面孔，整畦菜被拔爛\",\"legal\":\"固定簽約工班，長期配合，熟悉產銷班作業標準！\"},{\"item\":\"衛生規範\",\"illegal\":\"無衛生防護概念，抽菸檳榔進溫室恐遭吊銷有機標章\",\"legal\":\"嚴格遵循有機栽培規範，專用防護與工具定期消毒！\"},{\"item\":\"包裝效能\",\"illegal\":\"只能粗採，無法進行超市規格截切與貼標\",\"legal\":\"支援室內預冷清洗、電子秤重、貼標封口完整作業！\"},{\"item\":\"通路供貨\",\"illegal\":\"因缺工延遲出貨被量販店罰款甚至斷單\",\"legal\":\"保證出勤合約，供應大台北全聯與學校午餐零脫班！\"},{\"item\":\"會計核銷\",\"illegal\":\"私人工頭無發票，農業公司無法列報生產費用\",\"legal\":\"合法合作社進項憑證，完整扣抵 5% 營業稅！\"}]",
                "廖國安（阿安班長）",
                "桃園八德有機蔬菜產銷班第 3 班 班長",
                "【預期效益示意，非實際客戶見證】「我們天天都要交 3 萬把有機青江菜到全聯，只要一天工人沒來我就準備賠錢！自從跟智農派工簽了 120 工時常態包，工班每天 7 點準時到溫室報到。工人手腳乾淨又快，挑菜切菜比老手還俐落。現在我們整個八德有機產銷班都安心交給智農！」",
                "03-368-2111 / 0932-111-222",
                "桃園市八德區興豐路 827 號 (八德區農會推廣部對面)",
                "active",
                "2026-09-02"
            );

            Brochure dm3 = new Brochure(
                "dm-drone-2026",
                "【嘉南平原大面積契作農田】大疆 T50 大型無人機植保與 80hp 機耕深耕包",
                "科技農業正規軍！RTK 公分級高精度導航 ╳ 雙離心大流量噴霧 ╳ 附完整飛行軌跡產銷履歷",
                "智慧機耕",
                "水稻、雜糧、硬質玉米",
                "雲林元長、嘉義太保、台南後壁",
                "大面積契作大農、育苗中心、產銷班、代耕代噴需求農戶",
                "★ 科技代噴良率達標保證",
                "🛸",
                "indigo",
                "https://images.unsplash.com/photo-1527061011665-3652c757a4d4?w=800&auto=format&fit=crop&q=80",
                28800,
                25200,
                20,
                5040,
                30,
                2,
                1,
                "採用 DJI Agras T50 大型農業植保無人機（40 公斤播撒／50 公升噴霧）搭配久保田 80hp 曳引機。雙飛手持有民航局與農業部雙專業證照，完工提供精準飛行圖資與防漂移履歷！",
                "[{\"icon\":\"🛸\",\"title\":\"保證 1：雙證照合規飛手，投保 5,000 萬責任險\",\"desc\":\"全體飛手持農業藥劑代噴證照與民航局 G2 高級專業操作證，合法合規施作。\"},{\"icon\":\"🛰️\",\"title\":\"保證 2：RTK 公分級航跡定位，零漏噴零重疊\",\"desc\":\"架設高精度地面差分基站，航線精度達 2.5cm，均勻附著作物葉背。\"},{\"icon\":\"🚜\",\"title\":\"保證 3：深耕整地與高壓噴藥一貫化統包作業\",\"desc\":\"80 匹馬力迴轉式碎土深耕達 30 公分，打破犁底層，提升根系抗倒伏力。\"},{\"icon\":\"📊\",\"title\":\"保證 4：施作後 24 小時出具數位圖資防驗收爭議\",\"desc\":\"無人機飛行軌跡、用藥量、施作面積與天候感測數據即時匯整為數位報告。\"}]",
                "[{\"item\":\"飛安合法性\",\"illegal\":\"黑飛黑噴無證照，違法遭民航局與防檢署重罰 30 萬\",\"legal\":\"合法民航局活動許可，雙飛手受訓納保，合法無慮！\"},{\"item\":\"噴灑精準度\",\"illegal\":\"目視手飛，常漏噴斑塊導致稻熱病擴散或藥害\",\"legal\":\"全自動 RTK 航線規畫，微滴超音波霧化，穿透力強！\"},{\"item\":\"周邊鄰田爭議\",\"illegal\":\"風大盲目噴灑，農藥飄移隔壁有機田被求償百萬\",\"legal\":\"配備微氣象超音波監測，陣風超標自動熔斷警報！\"},{\"item\":\"設備可靠度\",\"illegal\":\"舊型雜牌機故障摔機，延誤產季防治黃金 48 小時\",\"legal\":\"全新大疆 T50 雙機輪替，備用發電機與水車隨隊馳援！\"},{\"item\":\"產銷履歷認可\",\"illegal\":\"無法出具合法證明，米廠與糧商無法認可\",\"legal\":\"產出合規履歷圖資，糧商契作驗收直接快速過關！\"}]",
                "許世峰（峰哥隊長）",
                "元長雜糧機械化代耕隊 隊長",
                "【預期效益示意，非實際客戶見證】「我們元長種黑豆、水稻面積幾十甲，人工噴藥根本找不到人，工錢喊到一天 3500 還請不到人！智農的無人機隊早上 5 點來，2 個鐘頭直接把 15 甲地噴得整整齊齊，藥水全部均勻附著在葉子背面。還有一台 80 匹馬力的曳引機幫忙深耕，省下我至少 4 天工！這才是現代農業的樣子！」",
                "05-788-5678 / 0918-668-999",
                "雲林縣元長鄉中山路 66 號 (農會特作推廣中心旁)",
                "active",
                "2026-09-03"
            );

            Brochure dm4 = new Brochure(
                "dm-coldchain-2026",
                "【高屏旗山/高樹採後冷鏈物流】生鮮蔬果清洗預冷與外銷截切包裝 80 工時包",
                "防颱防汛搶收必備！15°C 恆溫無塵分級 ╳ 急速差壓預冷保鮮 ╳ 風雨天候自動轉接零停工",
                "冷鏈防汛",
                "鳳梨、香蕉、洋蔥、青蔥",
                "高雄旗山、屏東高樹、屏南冷鏈物流園區",
                "農會運銷部、外銷貿易商、全聯鮮食截切廠、產銷合作社",
                "★ 防颱 24 小時快速馳援保證",
                "❄️",
                "cyan",
                "https://images.unsplash.com/photo-1542838132-92c53300491e?w=800&auto=format&fit=crop&q=80",
                25200,
                25200,
                20,
                5040,
                80,
                5,
                2,
                "解決產季爆量與颱風來襲搶收之冷鏈集貨瓶頸！配備標準生鮮截切冷房、光波自動秤重機與急速預冷庫，熟練包裝工班進駐，確保蔬果採後零失溫腐爛。",
                "[{\"icon\":\"❄️\",\"title\":\"保證 1：採收後 2 小時內完成核心溫度降至 12°C\",\"desc\":\"差壓預冷技術迅速帶走田間熱，抑制乙烯釋放，貨架壽命提升 1.5 倍！\"},{\"icon\":\"🚨\",\"title\":\"保證 2：颱風警報發布 24 小時啟動防汛搶收包裝\",\"desc\":\"機動調度冷鏈工班進駐集貨場，全天候 2 班制排程，確保搶收果品及時封箱！\"},{\"icon\":\"🔬\",\"title\":\"保證 3：通過 ISO 22000 / HACCP 衛生標準規範\",\"desc\":\"恆溫無塵作業區、食品級洗果消毒水槽與金屬探測機，符合出口嚴格檢驗。\"},{\"icon\":\"🚚\",\"title\":\"保證 4：低溫冷藏車物流直通大台北拍賣市場與碼頭\",\"desc\":\"配合溫控物流車隊全程 4°C 冷鏈保溫，確保到貨品質特級零退件！\"}]",
                "[{\"item\":\"降溫保鮮技術\",\"illegal\":\"常溫堆放集貨場，芒果鳳梨悶熱發酵爛果率達 20%\",\"legal\":\"進場即入差壓預冷庫，急速降溫，保鮮率提升至 98%！\"},{\"item\":\"颱風防汛動員\",\"illegal\":\"颱風前夕地下工頭坐地起價，加價也不願加班包裝\",\"legal\":\"簽約保障 24 小時防汛應變機制，工班兩班制連續運作！\"},{\"item\":\"衛生與分級\",\"illegal\":\"髒亂地面人工肉眼挑選，大小不一，出口遭退貨銷毀\",\"legal\":\"光波自動分級秤重、清洗風乾、真空包裝一貫化作業！\"},{\"item\":\"天候轉接機能\",\"illegal\":\"雨天無處可去，田間採收工白放無薪假\",\"legal\":\"田間一旦遇雨，專車 15 分鐘轉進室內冷鏈場無縫作業！\"},{\"item\":\"合約履約保障\",\"illegal\":\"口頭承諾包裝量，出貨延遲導致空運/海運艙位報廢\",\"legal\":\"SLA 履約承諾，保證每日包裝裝箱量，違約依損賠償！\"}]",
                "洪錦華（洪課長）",
                "屏南生鮮物流合作中心 供銷課長",
                "【預期效益示意，非實際客戶見證】「每年 7 月防颱搶收最要命！以前只要遇到颱風，幾十噸鳳梨香蕉堆在集貨場，找不到人包裝，看著它爛掉眼淚都掉下來。今年跟智農派工簽了冷鏈 80 工時包，颱風警報一發，5 位熟手包裝工當天下午立刻報到，兩天幫我們包了 1,800 箱外銷金鑽鳳梨！這才是真正能救命的現代農業冷鏈！」",
                "07-661-8888 / 0928-333-444",
                "高雄市旗山區中華路 520 號 (屏南冷鏈物流樞紐)",
                "active",
                "2026-09-04"
            );

            saveMissing(brochureRepository, List.of(dm1, dm2, dm3, dm4), Brochure::getId);
        }
    }

    private void seedWeatherStates() {
        {
            String defaultRegions = "[{\"region\":\"屏東枋山\",\"crop\":\"🥭 愛文芒果專區\",\"temp\":\"30°C\",\"icon\":\"⛅\",\"condition\":\"多雲時晴\",\"rainChance\":\"20%\",\"humidity\":\"68%\",\"uv\":\"中量 (6)\",\"advice\":\"果園採收與套袋作業正常推進，果實外觀品相優良。\"},{\"region\":\"桃園八德\",\"crop\":\"🥬 設施有機蔬菜\",\"temp\":\"26°C\",\"icon\":\"⛅\",\"condition\":\"多雲舒適\",\"rainChance\":\"20%\",\"humidity\":\"68%\",\"uv\":\"中量 (5)\",\"advice\":\"溫室通風良好，持續精緻修葉分級與生鮮冷鏈直配。\"},{\"region\":\"宜蘭三星\",\"crop\":\"🌱 三星青蔥專班\",\"temp\":\"25°C\",\"icon\":\"⛅\",\"condition\":\"陰天多雲\",\"rainChance\":\"25%\",\"humidity\":\"75%\",\"uv\":\"低量 (4)\",\"advice\":\"氣溫涼爽極利拔蔥，洗蔥機水線高效作業中。\"},{\"region\":\"雲林元長\",\"crop\":\"🫑 設施彩椒專區\",\"temp\":\"28°C\",\"icon\":\"⛅\",\"condition\":\"多雲微風\",\"rainChance\":\"15%\",\"humidity\":\"65%\",\"uv\":\"中量 (6)\",\"advice\":\"設施內溫度適宜，進行吊蔓修葉與病蟲害巡視。\"},{\"region\":\"新竹峨眉\",\"crop\":\"🍵 東方美人茶園\",\"temp\":\"26°C\",\"icon\":\"⛅\",\"condition\":\"多雲風輕\",\"rainChance\":\"15%\",\"humidity\":\"65%\",\"uv\":\"中量 (5)\",\"advice\":\"適合大疆 T50 執行大面積超低空精準施肥作業。\"}]";

            WeatherState wsActive = new WeatherState(
                    "WS-ACTIVE",
                    "cloudy",
                    "舒適多雲時晴",
                    "N",
                    "舒適多雲 26°C (微風涼爽宜農作)",
                    "normal",
                    "🟢 舒適天候正常出勤中・氣溫宜人作業稼動率高・無人機航測與田間管理順暢",
                    62500,
                    160,
                    defaultRegions,
                    java.time.LocalDateTime.now()
            );

            WeatherState wsSunny = new WeatherState(
                    "WS-SUNNY",
                    "sunny",
                    "全台晴朗炎熱",
                    "N",
                    "全台晴朗 31°C (高溫無雨)",
                    "normal",
                    "🟢 全台晴朗田間作業中・5 大核心產區出勤率 100%・注意防曬與定時補水",
                    62500,
                    160,
                    null,
                    java.time.LocalDateTime.now()
            );

            WeatherState wsRain = new WeatherState(
                    "WS-HEAVY-RAIN",
                    "heavy_rain",
                    "滯留鋒面大暴雨 (避險模式)",
                    "Y",
                    "暴雨警戒 22°C (滯留鋒面豪雨特報)",
                    "warning",
                    "⚡ 突發暴雨避險啟動：全體工班已專車轉派八德蔬果預冷冷鏈包裝線，出勤率 100%，零閒置虧損！保全營收 NT$ 62,500！",
                    62500,
                    160,
                    null,
                    java.time.LocalDateTime.now()
            );

            saveMissing(weatherStateRepository, java.util.List.of(wsActive, wsSunny, wsRain), WeatherState::getId);
        }
    }

    private void seedAttendance() {
        // 出勤紀錄為自動編號，無法依 ID 補缺：僅在完全無資料時灌入示範紀錄
        if (attendanceRepository.count() == 0) {
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.LocalDate yest = today.minusDays(1);

            Attendance a1 = new Attendance("W001", yest.atTime(6, 45), "八德有機蔬菜專區 (24.9351°N, 121.2842°E)", 2.4);
            a1.completeShift(yest.atTime(15, 30), "阮文雄");

            Attendance a2 = new Attendance("W026", yest.atTime(6, 30), "枋山愛文芒果產區 (22.2612°N, 120.6543°E)", 3.1);
            a2.completeShift(yest.atTime(16, 0), "林小祥 (帶工領班)");

            Attendance a3 = new Attendance("W030", yest.atTime(7, 0), "八德有機蔬菜專區 (24.9351°N, 121.2842°E)", 2.8);
            a3.completeShift(yest.atTime(15, 30), "張志明 (農業勞務工)");

            // 今日在線出勤
            Attendance a4 = new Attendance("W001", today.atTime(6, 50), "八德有機蔬菜專區 (24.9351°N, 121.2842°E)", 2.1);
            Attendance a5 = new Attendance("W026", today.atTime(6, 40), "枋山愛文芒果產區 (22.2612°N, 120.6543°E)", 3.0);
            Attendance a6 = new Attendance("W030", today.atTime(6, 55), "八德有機蔬菜專區 (24.9351°N, 121.2842°E)", 2.5);

            attendanceRepository.saveAll(java.util.List.of(a1, a2, a3, a4, a5, a6));
        }
    }
}
