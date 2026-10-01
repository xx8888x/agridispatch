package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.Crop;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.CropRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.security.AuthContext;
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
    /** 單筆訂單面積上限（分地），防止誤植或惡意輸入造成金額溢位 */
    public static final double MAX_AREA = 10_000;

    private final OrderRepository orderRepository;
    private final CropRepository cropRepository;
    private final AgriSettings settings;
    private final InsuranceService insuranceService;
    private final Object idLock = new Object();

    public OrderService(OrderRepository orderRepository, CropRepository cropRepository, AgriSettings settings,
                        InsuranceService insuranceService) {
        this.orderRepository = orderRepository;
        this.cropRepository = cropRepository;
        this.settings = settings;
        this.insuranceService = insuranceService;
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
                "suggestedTeam", "team_a",
                "suggestedTeamName", "工班 A 組 (桃園溫室蔬菜組)",
                "rateNote", "標準牌價 (早鳥 9 折)"
            ),
            Map.of(
                "id", "tillage_planting",
                "name", "【整地種植】曳引機深耕與定植",
                "icon", "🌱",
                "description", "大型曳引機深耕碎土、起壟作畦、鋪設抑草蓆、菜苗定植、水稻插秧。",
                "suggestedTeam", "team_c",
                "suggestedTeamName", "工班 C 組 (機耕無人機隊)",
                "rateNote", "專業機耕或時薪 $310"
            ),
            Map.of(
                "id", "pruning_bagging",
                "name", "【田間管理】修枝疏果與引蔓套袋",
                "icon", "✂️",
                "description", "果樹修枝剪梢、疏花疏果、立棚架引蔓、芒果/水梨/葡萄防蟲套袋。",
                "suggestedTeam", "team_b",
                "suggestedTeamName", "工班 B 組 (跨區專線組)",
                "rateNote", "精細人工工時"
            ),
            Map.of(
                "id", "drone_pest_control",
                "name", "【科技植保】無人機精準噴藥施肥",
                "icon", "🛸",
                "description", "專業植保機低空定點噴藥、粒肥精準拋撒、土壤改良劑代噴，附 GPS 航跡報告。",
                "suggestedTeam", "team_c",
                "suggestedTeamName", "工班 C 組 (機耕無人機隊)",
                "rateNote", "成果代耕 ($800/分地)"
            ),
            Map.of(
                "id", "cold_chain_pack",
                "name", "【冷鏈包裝】室內預冷與生鮮截切",
                "icon", "❄️",
                "description", "蔬果預冷入庫、自動光波秤重、生鮮超市小包裝、外銷標準貼標封箱。",
                "suggestedTeam", "team_d",
                "suggestedTeamName", "工班 D 組 (冷鏈常態組)",
                "rateNote", "雨天避險替代作業"
            ),
            Map.of(
                "id", "typhoon_emergency",
                "name", "【緊急救火】防颱搶收 24H 馳援",
                "icon", "🚨",
                "description", "颱風豪雨前夕 24H 徹夜搶收、防寒防霜覆蓋、農田清溝排水，緊急跨區馳援。",
                "suggestedTeam", "team_b",
                "suggestedTeamName", "全工班跨區緊急馳援",
                "rateNote", "緊急救火工時 ($360/hr)"
            )
        );
    }

    public Map<String, Object> calculateOrder(String cropId, double area, String plan, String zone) {
        return calculateOrder(cropId, area, plan, zone, "harvest_grading");
    }

    public Map<String, Object> calculateOrder(String cropId, double area, String plan, String zone, String dispatchCategory) {
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

        // 依據派工類別調整費率與工班
        String categoryName = "【採收分級】田間採收與外銷分級";
        String suggestedTeam = "team_a";
        String suggestedTeamName = "工班 A 組 (桃園溫室蔬菜組)";

        switch (dispatchCategory) {
            case "tillage_planting":
                categoryName = "【整地種植】曳引機深耕與定植";
                suggestedTeam = "team_c";
                suggestedTeamName = "工班 C 組 (機耕無人機隊)";
                break;
            case "pruning_bagging":
                categoryName = "【田間管理】修枝疏果與引蔓套袋";
                suggestedTeam = "team_b";
                suggestedTeamName = "工班 B 組 (跨區專線組)";
                break;
            case "drone_pest_control":
                categoryName = "【科技植保】無人機精準噴藥施肥";
                suggestedTeam = "team_c";
                suggestedTeamName = "工班 C 組 (機耕無人機隊)";
                break;
            case "cold_chain_pack":
                categoryName = "【冷鏈包裝】室內預冷與生鮮截切";
                suggestedTeam = "team_d";
                suggestedTeamName = "工班 D 組 (冷鏈常態組)";
                break;
            case "typhoon_emergency":
                categoryName = "【緊急救火】防颱搶收 24H 馳援";
                suggestedTeam = "team_b";
                suggestedTeamName = "全工班跨區緊急馳援";
                unitRate = Math.max(unitRate, 360); // 緊急救火工時費率
                break;
            default:
                break;
        }

        int totalHours = (int) Math.round(baseHours * area);
        int suggestedWorkers = Math.max(2, (int) Math.round(workersPerFen * (area > 3 ? 1.5 : 1)));
        double estDays = Math.round((totalHours / (suggestedWorkers * 8.0)) * 10.0) / 10.0;

        double discount = 1.0;
        String planName = "【Tier 1】產季早鳥工時包 (9折・保證出工)";
        int surcharge = "cross_region".equals(zone) ? 1200 : 0;

        if ("early_bird".equals(plan)) {
            discount = 0.90;
            planName = "【Tier 1】核心外展移工早鳥包 (預售 9 折・20%定金鎖定)";
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
        data.put("suggestedWorkers", suggestedWorkers);
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
        return orderRepository.save(order);
    }

    /** 定金付款成功（僅由 PaymentService 呼叫） */
    public Order markDepositPaid(Order order) {
        order.setDepositPaid(true);
        if (PENDING.equals(order.getStatus())) {
            order.setStatus(CONFIRMED);
        }
        return orderRepository.save(order);
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
        if (input.getFarmerName() == null || input.getFarmerName().isBlank()) {
            throw ApiException.badRequest("農場主姓名 (farmerName) 為必填欄位");
        }
        Order order = new Order();
        order.setFarmerId(farmerId);
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
        order.setCreatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        if (order.getForemanName() == null || order.getForemanName().isBlank()) {
            order.setForemanName(order.getFarmerName() + " (本人帶隊)");
        }
        if (order.getForemanPhone() == null || order.getForemanPhone().isBlank()) {
            order.setForemanPhone(order.getPhone());
        }

        Map<String, Object> calc = applyPricing(order); // 驗證作物 / 面積 / 方案並精算金額
        if (order.getAssignedTeamId() == null || order.getAssignedTeamId().isBlank()) {
            order.setAssignedTeamId((String) calc.get("suggestedTeam"));
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
        if (pricingChanged && existing.isDepositPaid()) {
            throw ApiException.conflict("已付定金的訂單不可變更作物、面積或方案，請聯繫調度中心");
        }

        if (updated.getFarmerName() != null && !updated.getFarmerName().isBlank()) existing.setFarmerName(updated.getFarmerName());
        if (updated.getPhone() != null) existing.setPhone(updated.getPhone());
        if (updated.getFarmLocation() != null) existing.setFarmLocation(updated.getFarmLocation());
        if (updated.getForemanName() != null) existing.setForemanName(updated.getForemanName());
        if (updated.getForemanPhone() != null) existing.setForemanPhone(updated.getForemanPhone());
        if (updated.getForemanNotes() != null) existing.setForemanNotes(updated.getForemanNotes());
        if (isAdmin && updated.getAssignedTeamId() != null) existing.setAssignedTeamId(updated.getAssignedTeamId());

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

    private Map<String, Object> applyPricing(Order order) {
        Map<String, Object> calc = calculateOrder(order.getCropId(), order.getArea(), order.getPlan(),
                order.getTransportZone(), order.getDispatchCategory());
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
        if (ctx.isAdmin() || ctx.isWorker() || isOwner(ctx, order)) return;
        throw ApiException.forbidden();
    }

    /** 付款 / 修改 / 取消：admin 或訂單擁有者 */
    public void checkOwnerOrAdmin(AuthContext ctx, Order order) {
        if (ctx.isAdmin() || isOwner(ctx, order)) return;
        throw ApiException.forbidden();
    }

    public List<Order> getOrdersVisibleTo(AuthContext ctx) {
        List<Order> all = orderRepository.findAll();
        if (ctx.isAdmin() || ctx.isWorker()) return all;
        if (ctx.isFarmer()) return all.stream().filter(o -> isOwner(ctx, o)).toList();
        return List.of();
    }
}
