package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.Crop;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.CropRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.*;

@Service
@Transactional
public class OrderService {
    public static final Set<String> PLANS = Set.of("early_bird", "hourly_flex", "turnkey");
    public static final Set<String> CATEGORIES = Set.of("harvest_grading", "tillage_planting", "pruning_bagging",
            "drone_pest_control", "cold_chain_pack", "typhoon_emergency");
    /**
     * 各派工類別的慣用工班（示範資料的 team_a～team_d）：只在媒合排序最後一關當同分時的偏好，
     * 建議工班一律取自實際存在的工班（MatchingService.suggestTeamId），這些代號不存在也不影響。
     */
    public static final Map<String, String> CATEGORY_DEFAULT_TEAM = Map.of(
            "harvest_grading", "team_a", "tillage_planting", "team_c", "pruning_bagging", "team_b",
            "drone_pest_control", "team_c", "cold_chain_pack", "team_d", "typhoon_emergency", "team_b");
    /** 單筆訂單面積上限（分地），防止誤植或惡意輸入造成金額溢位 */
    public static final double MAX_AREA = 10_000;
    /** 產季早鳥工時包單價（元/hr）：不打折，全案統一 315，高於 F2 v2.3 方案 C2 損益兩平價約 306 */
    public static final int EARLY_BIRD_RATE = 315;
    /** 單張訂單可指定的每日工人數上限（與前端一致） */
    public static final int MAX_REQUESTED_WORKERS = 30;

    private final OrderRepository orderRepository;
    private final CropRepository cropRepository;
    private final AgriSettings settings;
    private final InsuranceService insuranceService;
    private final LineNotifyService lineNotifyService;
    private final Object idLock = new Object();

    private final com.agridispatch.repository.BrochureRepository brochureRepository;
    private final WorkerRepository workerRepository;
    private final com.agridispatch.repository.DispatchTeamRepository teamRepository;
    private final com.agridispatch.repository.FarmRepository farmRepository;
    private final ObjectProvider<MatchingService> matchingProvider;

    /**
     * 工時包（文宣 DM）對應的作物與派工類別、預設面積：{cropId, dispatchCategory, area}。
     * 金額、工時、定金、建議人數取自該 DM 本身（Brochure），所以文宣標價就是成立金額；
     * 管理後台改 DM 價格，之後成立的訂單隨之改變。不在表內的 DM（自訂文宣）尚未開放線上預約。
     */
    private static final Map<String, String[]> PACKAGE_SPECS = Map.of(
            "dm-mango-2026", new String[]{"mango", "harvest_grading", "3.5"},
            "dm-greenhouse-2026", new String[]{"taoyuan_veg", "tillage_planting", "3.0"},
            "dm-drone-2026", new String[]{"rice", "drone_pest_control", "5.0"},
            "dm-coldchain-2026", new String[]{"pineapple", "cold_chain_pack", "4.0"});

    public OrderService(OrderRepository orderRepository, CropRepository cropRepository, AgriSettings settings,
                        InsuranceService insuranceService, LineNotifyService lineNotifyService,
                        com.agridispatch.repository.BrochureRepository brochureRepository,
                        WorkerRepository workerRepository,
                        com.agridispatch.repository.DispatchTeamRepository teamRepository,
                        com.agridispatch.repository.FarmRepository farmRepository,
                        ObjectProvider<MatchingService> matchingProvider) {
        this.farmRepository = farmRepository;
        this.matchingProvider = matchingProvider;
        this.orderRepository = orderRepository;
        this.teamRepository = teamRepository;
        this.workerRepository = workerRepository;
        this.brochureRepository = brochureRepository;
        this.cropRepository = cropRepository;
        this.settings = settings;
        this.insuranceService = insuranceService;
        this.lineNotifyService = lineNotifyService;
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    public Optional<Order> getOrderById(String id) {
        return orderRepository.findById(id);
    }

    public List<Map<String, Object>> getDispatchCategories() {
        return List.of(
            Map.of(
                "id", "harvest_grading",
                "name", "【採收分級】田間採收與外銷分級",
                "icon", "🥬",
                "description", "蔬菜採收、果實留蒂剪摘、田間初級篩選去老葉、標準規格分級裝箱。",
                "rateNote", "早鳥工時包 $315/hr (鎖定名額・優先排班)"
            ),
            Map.of(
                "id", "tillage_planting",
                "name", "【整地種植】曳引機深耕與定植",
                "icon", "🌱",
                "description", "大型曳引機深耕碎土、起壟作畦、鋪設抑草蓆、菜苗定植、水稻插秧。",
                "rateNote", "專業機耕或時薪 $310"
            ),
            Map.of(
                "id", "pruning_bagging",
                "name", "【田間管理】修枝疏果與引蔓套袋",
                "icon", "✂️",
                "description", "果樹修枝剪梢、疏花疏果、立棚架引蔓、芒果/水梨/葡萄防蟲套袋。",
                "rateNote", "精細人工工時"
            ),
            Map.of(
                "id", "drone_pest_control",
                "name", "【科技植保】無人機精準噴藥施肥",
                "icon", "🛸",
                "description", "專業植保機低空定點噴藥、粒肥精準拋撒、土壤改良劑代噴，附 GPS 航跡報告。",
                "rateNote", "成果代耕 ($800/分地)"
            ),
            Map.of(
                "id", "cold_chain_pack",
                "name", "【冷鏈包裝】室內預冷與生鮮截切",
                "icon", "❄️",
                "description", "蔬果預冷入庫、自動光波秤重、生鮮超市小包裝、外銷標準貼標封箱。",
                "rateNote", "雨天避險替代作業"
            ),
            Map.of(
                "id", "typhoon_emergency",
                "name", "【緊急救火】防颱搶收 24H 馳援",
                "icon", "🚨",
                "description", "颱風豪雨前夕 24H 徹夜搶收、防寒防霜覆蓋、農田清溝排水，緊急跨區馳援。",
                "rateNote", "緊急救火工時 ($360/hr)"
            )
        );
    }

    public Map<String, Object> calculateOrder(String cropId, double area, String plan, String zone) {
        return calculateOrder(cropId, area, plan, zone, "harvest_grading");
    }

    public Map<String, Object> calculateOrder(String cropId, double area, String plan, String zone, String dispatchCategory) {
        return calculateOrder(cropId, area, plan, zone, dispatchCategory, null);
    }

    /** 工時包試算：工時、價格、定金取自 DM；人數預設為 DM 建議編制，可由 requestedWorkers 覆寫 */
    public Map<String, Object> calculatePackage(String packageId, String zone, Integer requestedWorkers) {
        String[] spec = PACKAGE_SPECS.get(packageId);
        if (spec == null) {
            throw ApiException.badRequest("此工時包尚未開放線上預約：" + packageId);
        }
        com.agridispatch.entity.Brochure b = brochureRepository.findById(packageId)
                .filter(x -> "active".equals(x.getStatus()))
                .orElseThrow(() -> ApiException.badRequest("工時包已下架或不存在：" + packageId));
        if (b.getHours() <= 0) {
            throw ApiException.badRequest("工時包資料不完整：" + packageId);
        }
        int crew = requestedWorkers != null ? requestedWorkers : Math.max(1, b.getWorkers());
        // 沿用面積試算取得作物／類別名稱、建議工班；金額與工時再以 DM 覆寫
        Map<String, Object> data = new LinkedHashMap<>(calculateOrder(spec[0], Double.parseDouble(spec[2]), "early_bird",
                zone, spec[1], crew));
        int surcharge = (int) data.get("transportSurcharge");
        String name = "【工時包】" + b.getTitle();
        data.put("packageId", packageId);
        data.put("plan", "early_bird");
        data.put("planName", name.length() > 100 ? name.substring(0, 100) : name);
        data.put("totalHours", b.getHours());
        data.put("suggestedWorkers", crew);
        data.put("requestedWorkers", requestedWorkers);
        data.put("estDays", Math.round((b.getHours() / (crew * 8.0)) * 10.0) / 10.0);
        data.put("workHours", b.getHours());
        data.put("minimumTopUpHours", 0);
        data.put("unitRate", (int) Math.round(b.getDiscountPrice() / (double) b.getHours()));
        data.put("standardTotal", b.getOriginalPrice() + surcharge);
        data.put("actualTotal", b.getDiscountPrice() + surcharge);
        data.put("deposit", b.getDepositAmount());
        data.put("savings", Math.max(0, b.getOriginalPrice() - b.getDiscountPrice()));
        return data;
    }

    public Map<String, Object> calculateOrder(String cropId, double area, String plan, String zone, String dispatchCategory,
                                              Integer requestedWorkers) {
        return calculateOrder(cropId, area, plan, zone, dispatchCategory, requestedWorkers, Order.FULL_DAY);
    }

    /** 時段代碼正規化：空白 = 整天；不認得回 400 */
    public static String normalizeSlot(String timeSlot) {
        if (timeSlot == null || timeSlot.isBlank()) return Order.FULL_DAY;
        String s = timeSlot.trim();
        if (!Order.TIME_SLOTS.contains(s)) throw ApiException.badRequest("時段 (timeSlot) 必須為 full_day / morning / afternoon");
        return s;
    }

    /**
     * 試算。requestedWorkers 為農場主指定的每日工人數（null = 由系統依面積估算）：
     * 整天：金額只看作業量（工時 × 時薪），人數不同只影響編制與預估天數（天數 = 工時 ÷ (人數 × 8)）。
     * 半日（上午／下午）：每人每天 4 小時，天數 = 工時 ÷ (人數 × 4) 無條件進位；
     *   最低計費每人每天 4 小時——計費工時 = 人數 × 天數 × 4（≥ 作業工時），差額為 minimumTopUpHours，
     *   讓工人跑一趟至少有半天工資【待確認：F2 尚未納入半日最低計費情境】。
     */
    public Map<String, Object> calculateOrder(String cropId, double area, String plan, String zone, String dispatchCategory,
                                              Integer requestedWorkers, String timeSlot) {
        String slot = normalizeSlot(timeSlot);
        if (requestedWorkers != null && (requestedWorkers < 1 || requestedWorkers > MAX_REQUESTED_WORKERS)) {
            throw ApiException.badRequest("每日工人數 (workers) 必須介於 1 ～ " + MAX_REQUESTED_WORKERS);
        }
        if (dispatchCategory == null || dispatchCategory.isBlank()) {
            dispatchCategory = "harvest_grading";
        }
        if (!CATEGORIES.contains(dispatchCategory)) {
            throw ApiException.badRequest("不支援的派工類別：" + dispatchCategory);
        }
        if (plan == null || plan.isBlank()) {
            plan = "early_bird";
        }
        if (!PLANS.contains(plan)) {
            throw ApiException.badRequest("方案 (plan) 必須為 early_bird / hourly_flex / turnkey");
        }
        if (zone == null || zone.isBlank()) {
            zone = "core_35km";
        }
        if (Double.isNaN(area) || Double.isInfinite(area) || area <= 0) {
            throw ApiException.badRequest("面積 (area) 必須大於 0");
        }
        if (area > MAX_AREA) {
            throw ApiException.badRequest("面積 (area) 超過上限 " + (int) MAX_AREA + " 分地");
        }
        if (cropId == null || cropId.isBlank()) {
            throw ApiException.badRequest("作物代號 (cropId) 為必填欄位");
        }
        // 作物不存在回 400（不再默默改用第一筆作物）
        Crop crop = cropRepository.findById(cropId)
                .orElseThrow(() -> ApiException.badRequest("查無作物代號：" + cropId));

        int baseHours = (crop != null) ? crop.getBaseHoursPerFen() : 20;
        int workersPerFen = (crop != null) ? crop.getWorkersPerFen() : 3;
        int unitRate = (crop != null) ? crop.getRate() : 290;

        // 依據派工類別調整費率（建議工班由實際工班排序決定，見下方）
        String categoryName = "【採收分級】田間採收與外銷分級";

        switch (dispatchCategory) {
            case "tillage_planting":
                categoryName = "【整地種植】曳引機深耕與定植";
                break;
            case "pruning_bagging":
                categoryName = "【田間管理】修枝疏果與引蔓套袋";
                break;
            case "drone_pest_control":
                categoryName = "【科技植保】無人機精準噴藥施肥";
                break;
            case "cold_chain_pack":
                categoryName = "【冷鏈包裝】室內預冷與生鮮截切";
                break;
            case "typhoon_emergency":
                categoryName = "【緊急救火】防颱搶收 24H 馳援";
                unitRate = Math.max(unitRate, 360); // 緊急救火工時費率
                break;
            default:
                break;
        }

        int totalHours = (int) Math.round(baseHours * area);
        int suggestedWorkers = requestedWorkers != null
                ? requestedWorkers
                : Math.max(2, (int) Math.round(workersPerFen * (area > 3 ? 1.5 : 1)));
        int workHours = totalHours;
        double estDays;
        if (Order.FULL_DAY.equals(slot)) {
            estDays = Math.round((totalHours / (suggestedWorkers * 8.0)) * 10.0) / 10.0;
        } else {
            int days = (int) Math.ceil(totalHours / (suggestedWorkers * 4.0));
            totalHours = suggestedWorkers * days * 4;   // 計費工時（最低計費：每人每天 4 小時）
            estDays = days;
        }

        // 建議工班：與媒合頁同一套排序，從實際存在的工班挑第一名；沒有任何工班時為待分派
        Order draft = new Order();
        draft.setDispatchCategory(dispatchCategory);
        draft.setCategoryName(categoryName);
        draft.setSuggestedWorkers(suggestedWorkers);
        draft.setTimeSlot(slot);
        String suggestedTeam = matchingProvider.getObject().suggestTeamId(draft);
        String suggestedTeamName = suggestedTeam == null ? "待分派（尚未建立工班）"
                : teamRepository.findById(suggestedTeam).map(com.agridispatch.entity.DispatchTeam::getName).orElse(suggestedTeam);

        double discount = 1.0;
        String planName = "【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)";
        int surcharge = "cross_region".equals(zone) ? 1200 : 0;

        if ("early_bird".equals(plan)) {
            // 早鳥不打折：統一 315 元/hr，誘因改為鎖定產季名額、優先排班、遲到免單、放鳥雙倍賠
            if (!"typhoon_emergency".equals(dispatchCategory)) {
                unitRate = EARLY_BIRD_RATE;
            }
            planName = "【Tier 1】核心外展移工早鳥包 ($315/hr・鎖定名額・優先排班・20%定金)";
        } else if ("hourly_flex".equals(plan)) {
            discount = 1.0;
            if (!"typhoon_emergency".equals(dispatchCategory)) {
                unitRate = 310;
            }
            planName = "【Tier 2】在地彈性兼職包 (隨叫隨派・時薪 $310・零底薪負擔)";
        } else if ("turnkey".equals(plan)) {
            discount = 0.95;
            planName = "【Tier 3】成果統包專業代耕 (無人機/機耕驗收付款・免監工)";
        }

        int standardTotal = (int) Math.round(totalHours * unitRate);
        int discountedTotal = (int) Math.round(standardTotal * discount);
        int actualTotal = discountedTotal + surcharge;
        int deposit = "early_bird".equals(plan) ? (int) Math.round(discountedTotal * 0.2)
                    : ("turnkey".equals(plan) ? (int) Math.round(discountedTotal * 0.3) : 0);
        int savings = standardTotal - discountedTotal;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cropId", crop != null ? crop.getId() : cropId);
        data.put("cropName", crop != null ? crop.getName() : "未指定作物");
        data.put("area", area);
        data.put("plan", plan);
        data.put("planName", planName);
        data.put("dispatchCategory", dispatchCategory);
        data.put("categoryName", categoryName);
        data.put("suggestedTeam", suggestedTeam);
        data.put("suggestedTeamName", suggestedTeamName);
        data.put("transportZone", zone);
        data.put("transportSurcharge", surcharge);
        data.put("totalHours", totalHours);
        data.put("workHours", workHours);
        data.put("minimumTopUpHours", totalHours - workHours);
        data.put("timeSlot", slot);
        data.put("timeSlotLabel", Order.slotLabel(slot));
        data.put("hoursPerDay", Order.hoursPerDay(slot));
        data.put("suggestedWorkers", suggestedWorkers);
        data.put("requestedWorkers", requestedWorkers);
        data.put("estDays", estDays);
        data.put("unitRate", unitRate);
        data.put("standardTotal", standardTotal + surcharge);
        data.put("actualTotal", actualTotal);
        data.put("deposit", deposit);
        data.put("savings", savings);

        return data;
    }

    // =========================================================================
    // 訂單狀態機
    //   pending（待付定金）→ confirmed（已排定）→ in_progress（履約中）→ completed（完工）
    //   任一未完工狀態 → cancelled（已取消）
    //   - 新訂單：需定金 → pending；免定金方案（hourly_flex 等）→ confirmed
    //   - 定金付款成功：pending → confirmed（已是 confirmed / in_progress 的舊資料僅標記已付款）
    //   - 其餘轉換只能經 admin 專用端點 PUT /api/orders/{id}/status
    // =========================================================================

    public static final String PENDING = "pending";
    public static final String CONFIRMED = "confirmed";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            PENDING, Set.of(CONFIRMED, CANCELLED),
            CONFIRMED, Set.of(IN_PROGRESS, CANCELLED),
            IN_PROGRESS, Set.of(COMPLETED, CANCELLED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of()
    );

    /** 狀態代碼轉中文（LINE 卡片等對外顯示用）；未知代碼原樣回傳 */
    public static String statusLabel(String status) {
        if (status == null) return "";
        return switch (status) {
            case PENDING -> "待付定金";
            case CONFIRMED -> "已排定";
            case IN_PROGRESS -> "履約中";
            case COMPLETED -> "已完工";
            case CANCELLED -> "已取消";
            default -> status;
        };
    }

    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** admin 明確變更訂單狀態 */
    public Order changeStatus(String id, String newStatus) {
        Order order = requireOrder(id);
        if (newStatus == null || !TRANSITIONS.containsKey(newStatus)) {
            throw ApiException.badRequest("status 必須為 pending / confirmed / in_progress / completed / cancelled");
        }
        if (newStatus.equals(order.getStatus())) {
            return order;
        }
        if (!canTransition(order.getStatus(), newStatus)) {
            throw ApiException.conflict("訂單狀態不可由 " + order.getStatus() + " 變更為 " + newStatus);
        }
        if (CONFIRMED.equals(newStatus) && order.getDepositAmount() > 0 && !order.isDepositPaid()) {
            throw ApiException.conflict("訂單尚未收到定金，無法轉為已排定");
        }
        if (IN_PROGRESS.equals(newStatus)) {
            // 派工前檢查：有出工名單時檢查名單成員，否則檢查已分派工班的每位成員；皆須完整加保才可出工
            if (!order.crewList().isEmpty()) {
                insuranceService.assertWorkersInsured(order.crewList(), java.time.LocalDate.now());
            } else {
                insuranceService.assertTeamInsured(order.getAssignedTeamId(), java.time.LocalDate.now());
            }
        }
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        lineNotifyService.orderStatusChanged(saved); // LINE 農場主帳號通知（背景執行，規格 05 第柒章）
        return saved;
    }

    /** 定金付款成功（僅由 PaymentService 呼叫） */
    public Order markDepositPaid(Order order) {
        order.setDepositPaid(true);
        if (PENDING.equals(order.getStatus())) {
            order.setStatus(CONFIRMED);
        }
        Order saved = orderRepository.save(order);
        lineNotifyService.depositPaid(saved); // LINE 農場主帳號通知（背景執行）
        return saved;
    }

    // =========================================================================
    // 建立 / 更新 / 刪除
    // =========================================================================

    public Order requireOrder(String id) {
        return orderRepository.findById(id).orElseThrow(() -> ApiException.notFound("找不到該訂單編號: " + id));
    }

    /**
     * 建立訂單：金額、狀態、定金一律由後端計算；client 傳入的 id / status / depositPaid / 金額欄位皆忽略
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order createOrder(Order input, String farmerId) {
        return createOrder(input, farmerId, null);
    }

    /**
     * @param preferred 農場主指名的工人（已由 PreferredWorkerService 驗證）；null 表示不指名。
     *                  client 傳入的 preferredWorkerId / preferredWorkerName 一律忽略，只以此參數為準
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order createOrder(Order input, String farmerId, com.agridispatch.entity.Worker preferred) {
        if (input.getFarmerName() == null || input.getFarmerName().isBlank()) {
            throw ApiException.badRequest("農場主姓名 (farmerName) 為必填欄位");
        }
        validateTextLengths(input);
        Order order = new Order();
        order.setFarmerId(farmerId);
        // 選的農場：須為下單農場主自己的農場（admin 代建且未指定農場主時，以農場的主人為準）；名稱存快照
        if (input.getFarmId() != null && !input.getFarmId().isBlank()) {
            com.agridispatch.entity.Farm farm = farmRepository.findById(input.getFarmId().trim())
                    .filter(f -> farmerId == null || farmerId.equals(f.getFarmerId()))
                    .orElseThrow(() -> ApiException.badRequest("找不到選擇的農場，請重新選擇"));
            if (farmerId == null) order.setFarmerId(farm.getFarmerId());
            order.setFarmId(farm.getId());
            order.setFarmName(farm.getName());
        }
        order.setFarmerName(input.getFarmerName().trim());
        order.setPhone(input.getPhone());
        order.setFarmLocation(input.getFarmLocation());
        order.setCropId(input.getCropId());
        order.setArea(input.getArea());
        order.setPlan(input.getPlan() == null || input.getPlan().isBlank() ? "early_bird" : input.getPlan());
        order.setTransportZone(input.getTransportZone() == null || input.getTransportZone().isBlank() ? "core_35km" : input.getTransportZone());
        order.setDispatchCategory(input.getDispatchCategory() == null || input.getDispatchCategory().isBlank()
                ? "harvest_grading" : input.getDispatchCategory());
        order.setForemanName(input.getForemanName());
        order.setForemanPhone(input.getForemanPhone());
        order.setForemanNotes(input.getForemanNotes());
        order.setAssignedTeamId(input.getAssignedTeamId());
        order.setRequestedWorkers(input.getRequestedWorkers());   // 範圍由試算驗證
        order.setTimeSlot(normalizeSlot(input.getTimeSlot()));
        order.setPackageId(input.getPackageId() == null || input.getPackageId().isBlank() ? null : input.getPackageId().trim());
        validatePeriod(input.getStartDate(), input.getEndDate());
        order.setStartDate(input.getStartDate());
        order.setEndDate(input.getEndDate());
        order.setCreatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        if (order.getForemanName() == null || order.getForemanName().isBlank()) {
            order.setForemanName(order.getFarmerName() + " (本人帶隊)");
        }
        if (order.getForemanPhone() == null || order.getForemanPhone().isBlank()) {
            order.setForemanPhone(order.getPhone());
        }

        Map<String, Object> calc = applyPricing(order); // 驗證作物 / 面積 / 方案並精算金額
        if (preferred != null) {
            order.setPreferredWorkerId(preferred.getId());
            order.setPreferredWorkerName(MatchingService.displayName(preferred));
            // 未指定工班時交給指名工人所屬工班，媒合時才能把他排進出工名單
            if ((order.getAssignedTeamId() == null || order.getAssignedTeamId().isBlank())
                    && preferred.getTeamId() != null && teamRepository.existsById(preferred.getTeamId())) {
                order.setAssignedTeamId(preferred.getTeamId());
            }
        }
        if (order.getAssignedTeamId() == null || order.getAssignedTeamId().isBlank()) {
            // 未指定工班：以這張訂單本身（類別、人數、工期、指名工人）跑媒合排序取第一名；沒有工班時為 null（待分派）
            order.setAssignedTeamId(matchingProvider.getObject().suggestTeamId(order));
        } else {
            requireTeam(order.getAssignedTeamId());
        }
        order.setDepositPaid(false);
        order.setStatus(order.getDepositAmount() > 0 ? PENDING : CONFIRMED);

        synchronized (idLock) {
            String prefix = "ORD-" + new SimpleDateFormat("yyyy").format(new Date()) + "-";
            order.setId(IdGenerator.next(prefix, 3, orderRepository.findIdsByPrefix(prefix), orderRepository::existsById));
            return orderRepository.saveAndFlush(order);
        }
    }

    /**
     * 更新訂單：不可由 client 直接改 depositPaid / status / 金額；
     * 作物、面積、方案、運輸區、派工類別變更時自動重算金額
     */
    public Order updateOrder(String id, Order updated, boolean isAdmin) {
        Order existing = requireOrder(id);
        if (CANCELLED.equals(existing.getStatus()) || COMPLETED.equals(existing.getStatus())) {
            throw ApiException.conflict("已取消或已完工的訂單不可修改");
        }
        if (updated.getArea() < 0 || Double.isNaN(updated.getArea())) {
            throw ApiException.badRequest("面積必須大於 0");
        }
        validateTextLengths(updated);
        if (existing.getSharedRosterId() != null && rosterSpecChanged(existing, updated)) {
            throw ApiException.conflict("共享班表的時段訂單不可修改作物、面積、方案、人數、工期或時段；請取消後重新認領");
        }

        boolean pricingChanged = false;
        if (updated.getCropId() != null && !updated.getCropId().equals(existing.getCropId())) {
            existing.setCropId(updated.getCropId());
            pricingChanged = true;
        }
        if (updated.getArea() > 0 && updated.getArea() != existing.getArea()) {
            existing.setArea(updated.getArea());
            pricingChanged = true;
        }
        if (updated.getPlan() != null && !updated.getPlan().equals(existing.getPlan())) {
            existing.setPlan(updated.getPlan());
            pricingChanged = true;
        }
        if (updated.getTransportZone() != null && !updated.getTransportZone().equals(existing.getTransportZone())) {
            existing.setTransportZone(updated.getTransportZone());
            pricingChanged = true;
        }
        if (updated.getDispatchCategory() != null && !updated.getDispatchCategory().equals(existing.getDispatchCategory())) {
            existing.setDispatchCategory(updated.getDispatchCategory());
            pricingChanged = true;
        }
        // 時段：影響計費工時與撞期，規則同工期（農場主只能在待付定金時改）
        if (updated.getTimeSlot() != null && !normalizeSlot(updated.getTimeSlot()).equals(existing.slot())) {
            if (!isAdmin && !PENDING.equals(existing.getStatus())) {
                throw ApiException.conflict("訂單已排定，時段調整請聯絡合作社（可能影響已安排的出工名單）");
            }
            existing.setTimeSlot(normalizeSlot(updated.getTimeSlot()));
            pricingChanged = true;
        }
        if (pricingChanged && existing.isDepositPaid()) {
            throw ApiException.conflict("已付定金的訂單不可變更作物、面積、方案或時段，請聯繫調度中心");
        }
        if (pricingChanged && existing.getPackageId() != null) {
            existing.setPackageId(null);   // 改成自訂規格：之後依作物與面積計價，不再適用工時包固定價
        }

        // 每日工人數：不影響金額；0 = 改回由系統估算。已排定的訂單只有 admin 能改（可能影響已安排的出工名單）
        boolean crewChanged = false;
        if (updated.getRequestedWorkers() != null) {
            Integer wanted = updated.getRequestedWorkers() == 0 ? null : updated.getRequestedWorkers();
            if (!java.util.Objects.equals(wanted, existing.getRequestedWorkers())) {
                if (!isAdmin && !PENDING.equals(existing.getStatus())) {
                    throw ApiException.conflict("訂單已排定，人數調整請聯絡合作社（可能影響已安排的出工名單）");
                }
                existing.setRequestedWorkers(wanted);
                crewChanged = true;
            }
        }

        if (updated.getFarmerName() != null && !updated.getFarmerName().isBlank()) existing.setFarmerName(updated.getFarmerName());
        if (updated.getPhone() != null) existing.setPhone(updated.getPhone());
        if (updated.getFarmLocation() != null) existing.setFarmLocation(updated.getFarmLocation());
        if (updated.getForemanName() != null) existing.setForemanName(updated.getForemanName());
        if (updated.getForemanPhone() != null) existing.setForemanPhone(updated.getForemanPhone());
        if (updated.getForemanNotes() != null) existing.setForemanNotes(updated.getForemanNotes());
        if (isAdmin && updated.getAssignedTeamId() != null) existing.setAssignedTeamId(requireTeam(updated.getAssignedTeamId()));
        if (updated.getStartDate() != null || updated.getEndDate() != null) {
            if (COMPLETED.equals(existing.getStatus()) || CANCELLED.equals(existing.getStatus())) {
                throw ApiException.conflict("已完工或已取消的訂單不可修改工期");
            }
            if (!isAdmin && !PENDING.equals(existing.getStatus())) {
                throw ApiException.conflict("訂單已排定，工期調整請聯絡合作社（可能影響已安排的出工名單）");
            }
            java.time.LocalDate start = updated.getStartDate() != null ? updated.getStartDate() : existing.getStartDate();
            java.time.LocalDate end = updated.getEndDate() != null ? updated.getEndDate() : existing.getEndDate();
            validatePeriod(start, end);
            existing.setStartDate(start);
            existing.setEndDate(end);
        }

        if (crewChanged && !pricingChanged) {
            // 只改人數：金額與狀態不動，只重算編制人數（同時驗證範圍）
            Map<String, Object> calc = quoteFor(existing);
            existing.setSuggestedWorkers((int) calc.get("suggestedWorkers"));
        }
        if (pricingChanged) {
            applyPricing(existing);
            if (!existing.isDepositPaid()) {
                // 重算後定金需求可能改變：需定金且尚未付 → pending；免定金 → confirmed
                if (PENDING.equals(existing.getStatus()) && existing.getDepositAmount() <= 0) existing.setStatus(CONFIRMED);
                else if (CONFIRMED.equals(existing.getStatus()) && existing.getDepositAmount() > 0) existing.setStatus(PENDING);
            }
        }
        return orderRepository.save(existing);
    }

    /** 共享班表訂單的規格是否有變動（只允許改聯絡資訊與現場交辦） */
    private static boolean rosterSpecChanged(Order existing, Order u) {
        return (u.getCropId() != null && !u.getCropId().equals(existing.getCropId()))
                || (u.getArea() > 0 && u.getArea() != existing.getArea())
                || (u.getPlan() != null && !u.getPlan().equals(existing.getPlan()))
                || (u.getTransportZone() != null && !u.getTransportZone().equals(existing.getTransportZone()))
                || (u.getDispatchCategory() != null && !u.getDispatchCategory().equals(existing.getDispatchCategory()))
                || (u.getRequestedWorkers() != null && !u.getRequestedWorkers().equals(existing.getRequestedWorkers()))
                || (u.getStartDate() != null && !u.getStartDate().equals(existing.getStartDate()))
                || (u.getEndDate() != null && !u.getEndDate().equals(existing.getEndDate()))
                || (u.getTimeSlot() != null && !normalizeSlot(u.getTimeSlot()).equals(existing.slot()))
                || (u.getAssignedTeamId() != null && !u.getAssignedTeamId().equals(existing.getAssignedTeamId()));
    }

    /** admin 硬刪除 */
    public void deleteOrder(String id) {
        if (!orderRepository.existsById(id)) {
            throw ApiException.notFound("找不到該訂單編號: " + id);
        }
        orderRepository.deleteById(id);
    }

    /** 農場主取消自己的訂單（保留紀錄，狀態改為 cancelled） */
    public Order cancelOrder(String id) {
        Order order = requireOrder(id);
        if (CANCELLED.equals(order.getStatus())) return order;
        if (!canTransition(order.getStatus(), CANCELLED) || IN_PROGRESS.equals(order.getStatus())) {
            throw ApiException.conflict("此訂單目前狀態 (" + order.getStatus() + ") 無法自行取消，請聯繫調度中心");
        }
        order.setStatus(CANCELLED);
        return orderRepository.save(order);
    }

    /** 依訂單目前規格取得報價：有工時包就用 DM 固定價，否則依作物與面積 */
    private Map<String, Object> quoteFor(Order order) {
        if (order.getPackageId() != null) {
            requirePackageSlot(order.slot());
            return calculatePackage(order.getPackageId(), order.getTransportZone(), order.getRequestedWorkers());
        }
        return calculateOrder(order.getCropId(), order.getArea(), order.getPlan(),
                order.getTransportZone(), order.getDispatchCategory(), order.getRequestedWorkers(), order.slot());
    }

    /**
     * 共享班表時段報價：整組工班到場，計費工時 = 工班人數 × 時段工時（上午／下午 4、整天 8）；
     * 拼團不打折，時薪與定金依方案（早鳥 315 元／20% 定金、彈性計時 310 元／免定金）。成果統包按驗收計價，不適用。
     */
    public Map<String, Object> quoteRosterSlot(int crewSize, String timeSlot, String plan) {
        String slot = normalizeSlot(timeSlot);
        if (plan == null || plan.isBlank()) plan = "early_bird";
        if (!"early_bird".equals(plan) && !"hourly_flex".equals(plan)) {
            throw ApiException.badRequest("共享班表只提供早鳥工時包或彈性計時（成果統包按驗收計價，不適用）");
        }
        int hours = crewSize * Order.hoursPerDay(slot);
        int rate = "early_bird".equals(plan) ? EARLY_BIRD_RATE : 310;
        int total = hours * rate;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plan", plan);
        data.put("planName", "early_bird".equals(plan)
                ? "【共享班表】早鳥工時包 ($315/hr・20%定金)" : "【共享班表】彈性計時 ($310/hr・免定金)");
        data.put("timeSlot", slot);
        data.put("timeSlotLabel", Order.slotLabel(slot));
        data.put("crewSize", crewSize);
        data.put("totalHours", hours);
        data.put("unitRate", rate);
        data.put("actualTotal", total);
        data.put("deposit", "early_bird".equals(plan) ? (int) Math.round(total * 0.2) : 0);
        return data;
    }

    /**
     * 建立共享班表的認領訂單（由 RosterService 驗證班表狀態、日期與時段未被認領後呼叫）：
     * 工班與出工名單沿用班表，工期就是認領的那一天，金額依 quoteRosterSlot
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order createRosterClaim(Order input, String farmerId, com.agridispatch.entity.SharedRoster roster,
                                   java.time.LocalDate day, String timeSlot) {
        if (input.getFarmerName() == null || input.getFarmerName().isBlank()) {
            throw ApiException.badRequest("農場主姓名 (farmerName) 為必填欄位");
        }
        validateTextLengths(input);
        Crop crop = cropRepository.findById(input.getCropId() == null ? "" : input.getCropId())
                .orElseThrow(() -> ApiException.badRequest("查無作物代號：" + input.getCropId()));
        double area = input.getArea();
        if (Double.isNaN(area) || area <= 0 || area > MAX_AREA) {
            throw ApiException.badRequest("面積 (area) 必須大於 0 且不超過 " + (int) MAX_AREA + " 分地");
        }
        List<String> crew = roster.crewList();
        Map<String, Object> q = quoteRosterSlot(crew.size(), timeSlot, input.getPlan());

        Order order = new Order();
        order.setFarmerId(farmerId);
        if (input.getFarmId() != null && !input.getFarmId().isBlank()) {
            com.agridispatch.entity.Farm farm = farmRepository.findById(input.getFarmId().trim())
                    .filter(f -> farmerId.equals(f.getFarmerId()))
                    .orElseThrow(() -> ApiException.badRequest("找不到選擇的農場，請重新選擇"));
            order.setFarmId(farm.getId());
            order.setFarmName(farm.getName());
        }
        order.setFarmerName(input.getFarmerName().trim());
        order.setPhone(input.getPhone());
        order.setFarmLocation(input.getFarmLocation());
        order.setCropId(crop.getId());
        order.setCropName(crop.getName());
        order.setArea(area);
        order.setPlan((String) q.get("plan"));
        order.setPlanName((String) q.get("planName"));
        order.setDispatchCategory(roster.getDispatchCategory());
        order.setCategoryName(categoryNameOf(roster.getDispatchCategory()));
        order.setTransportZone("core_35km");
        order.setTransportSurcharge(0);
        order.setTotalHours((int) q.get("totalHours"));
        order.setSuggestedWorkers(crew.size());
        order.setRequestedWorkers(crew.size());
        order.setUnitRate((int) q.get("unitRate"));
        order.setStandardAmount((int) q.get("actualTotal"));
        order.setFinalAmount((int) q.get("actualTotal"));
        order.setDepositAmount((int) q.get("deposit"));
        order.setForemanName(input.getForemanName() == null || input.getForemanName().isBlank()
                ? order.getFarmerName() + " (本人帶隊)" : input.getForemanName());
        order.setForemanPhone(input.getForemanPhone() == null || input.getForemanPhone().isBlank()
                ? order.getPhone() : input.getForemanPhone());
        order.setForemanNotes(input.getForemanNotes());
        order.setAssignedTeamId(roster.getTeamId());
        order.setCrewWorkerIds(String.join(",", crew));
        order.setSharedRosterId(roster.getId());
        order.setStartDate(day);
        order.setEndDate(day);
        order.setTimeSlot((String) q.get("timeSlot"));
        order.setCreatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        order.setDepositPaid(false);
        order.setStatus(order.getDepositAmount() > 0 ? PENDING : CONFIRMED);
        synchronized (idLock) {
            String prefix = "ORD-" + new SimpleDateFormat("yyyy").format(new Date()) + "-";
            order.setId(IdGenerator.next(prefix, 3, orderRepository.findIdsByPrefix(prefix), orderRepository::existsById));
            return orderRepository.saveAndFlush(order);
        }
    }

    /** 派工類別代碼 → 名稱（與一般訂單試算的類別名稱一致） */
    public String categoryNameOf(String category) {
        for (Map<String, Object> c : getDispatchCategories()) {
            if (java.util.Objects.equals(c.get("id"), category)) return (String) c.get("name");
        }
        return category;
    }

    /** 工時包（DM 標價）以整天班編制，不提供半日時段 */
    public static void requirePackageSlot(String slot) {
        if (!Order.FULL_DAY.equals(slot)) throw ApiException.badRequest("工時包為整天班，不提供半日時段；半日請改用一般預約");
    }

    private Map<String, Object> applyPricing(Order order) {
        Map<String, Object> calc = quoteFor(order);
        if (order.getPackageId() != null) {
            // 工時包的作物、類別、面積、方案由 DM 決定，不接受前端另傳
            order.setCropId((String) calc.get("cropId"));
            order.setDispatchCategory((String) calc.get("dispatchCategory"));
            order.setArea((double) calc.get("area"));
            order.setPlan("early_bird");
        }
        order.setCropName((String) calc.get("cropName"));
        order.setPlanName((String) calc.get("planName"));
        order.setCategoryName((String) calc.get("categoryName"));
        order.setTotalHours((int) calc.get("totalHours"));
        order.setSuggestedWorkers((int) calc.get("suggestedWorkers"));
        order.setUnitRate((int) calc.get("unitRate"));
        order.setStandardAmount((int) calc.get("standardTotal"));
        order.setFinalAmount((int) calc.get("actualTotal"));
        order.setDepositAmount((int) calc.get("deposit"));
        order.setTransportSurcharge((int) calc.get("transportSurcharge"));
        return calc;
    }

    /** 工期：開工、完工日須同時提供，完工不可早於開工，單張訂單最長 366 天；皆未提供視為尚未排定 */
    private String requireTeam(String teamId) {
        if (!teamRepository.existsById(teamId)) throw ApiException.badRequest("查無工班：" + teamId);
        return teamId;
    }

    /** 文字欄位長度上限（未設 @Column 的欄位在 H2 為 varchar(255)），超過回 400 而不是資料庫錯誤 */
    static void validateTextLengths(Order o) {
        checkLength(o.getFarmerName(), 100, "農場主姓名");
        checkLength(o.getPhone(), 30, "聯絡電話");
        checkLength(o.getFarmLocation(), 255, "農地位置");
        checkLength(o.getForemanName(), 100, "現場對口姓名");
        checkLength(o.getForemanPhone(), 30, "現場對口電話");
        checkLength(o.getForemanNotes(), 255, "現場交辦事項");
    }

    private static void checkLength(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw ApiException.badRequest(label + "過長（上限 " + max + " 字）");
        }
    }

    static void validatePeriod(java.time.LocalDate start, java.time.LocalDate end) {
        if (start == null && end == null) return;
        if (start == null || end == null) throw ApiException.badRequest("開工日 (startDate) 與完工日 (endDate) 須同時提供");
        if (end.isBefore(start)) throw ApiException.badRequest("完工日不可早於開工日");
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 366) throw ApiException.badRequest("單張訂單工期不可超過一年");
    }

    // =========================================================================
    // 權限（規格第 5 節：farmer 只能查詢 / 付款自己的訂單）
    // =========================================================================

    /** farmer 是否為訂單擁有者；舊資料無 farmerId 時 demo 模式放行 */
    public boolean isOwner(AuthContext ctx, Order order) {
        if (ctx == null || !ctx.isFarmer()) return false;
        if (order.getFarmerId() == null) return settings.isDemoEnabled();
        return order.getFarmerId().equals(ctx.ref());
    }

    public void checkReadable(AuthContext ctx, Order order) {
        if (ctx.isStaff() || isOwner(ctx, order)) return;
        if (ctx.isWorker() && workerCanSee(ctx, order, workerTeamId(ctx))) return;
        throw ApiException.forbidden();
    }

    /**
     * 工人可見的訂單：正式模式只限自己工班承接或出工名單有自己的訂單（訂單含農場主電話、農地位置與金額）；
     * demo 模式維持全部可見，路演的本勞工作台一頁同時呈現三種身分的任務卡。
     */
    private boolean workerCanSee(AuthContext ctx, Order order, String teamId) {
        if (settings.isDemoEnabled()) return true;
        if (teamId != null && teamId.equals(order.getAssignedTeamId())) return true;
        String crew = order.getCrewWorkerIds();
        return crew != null && Arrays.asList(crew.split(",")).contains(ctx.ref());
    }

    private String workerTeamId(AuthContext ctx) {
        if (ctx.ref() == null) return null;
        return workerRepository.findById(ctx.ref()).map(w -> w.getTeamId()).orElse(null);
    }

    /** 付款 / 修改 / 取消：admin 或訂單擁有者 */
    public void checkOwnerOrAdmin(AuthContext ctx, Order order) {
        if (ctx.isStaff() || isOwner(ctx, order)) return;
        throw ApiException.forbidden();
    }

    public List<Order> getOrdersVisibleTo(AuthContext ctx) {
        List<Order> all = orderRepository.findAll();
        if (ctx.isStaff()) return all;
        if (ctx.isFarmer()) return all.stream().filter(o -> isOwner(ctx, o)).toList();
        if (ctx.isWorker()) {
            String teamId = workerTeamId(ctx);
            return all.stream().filter(o -> workerCanSee(ctx, o, teamId)).toList();
        }
        return List.of();
    }
}
