package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.*;
import com.agridispatch.repository.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 派工媒合：依技能標籤、工班評價與負載，為訂單推薦工班與出工名單（管理員確認後儲存）。
 *
 * 工班排序（先過硬性條件：成員皆完整加保、人數足夠）：
 *   該類別有效熟手人數 → 資深熟手人數 → 工班評價平均 → 同期間訂單數（少者優先；未排工期時改看施工中訂單數）→ 該類別預設工班
 * 工期撞期：已排入其他工期重疊訂單出工名單的工人，不列入名單、也不計入合格人數
 * 出工名單（先過硬性條件：工作許可有效、完整加保）：
 *   必含一位領班；其餘依 資深＞熟手＞培養中 → 領班「需加強」比例低 → 「優」比例高 → 該類別時數多
 *   名單 ≥ 4 人時保留至少 1 個「培養中」名額，讓新進工人也能累積經驗（避免熟手壟斷）
 * 排序完全不使用國籍、性別、年齡；標籤只影響優先順序，不排除任何合格工人【待確認】。
 * 農場主指名（order.preferredWorkerId）：指名工人所屬工班排在合格工班第一位、指名工人排在出工名單第一位（角色「農場主指名」）；
 *   指名工人不合格或撞期時照常推薦其他人，並以 preferredWarning 提醒調度告知農場主。
 *   尚未排出工名單、但已指名的訂單，視同已佔用指名工人該工期（撞期檢查一併計入）。
 */
@Service
public class MatchingService {

    public static final int TRAINEE_SLOT_MIN_CREW = 4;
    private static final Set<String> ASSIGNABLE_STATUS = Set.of(OrderService.PENDING, OrderService.CONFIRMED, OrderService.IN_PROGRESS);

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final DispatchTeamRepository teamRepository;
    private final WorkerRepository workerRepository;
    private final InsuranceRecordRepository insuranceRecordRepository;
    private final InsuranceService insuranceService;
    private final SkillService skillService;
    private final RatingService ratingService;
    private final AgriSettings settings;

    public MatchingService(OrderService orderService, OrderRepository orderRepository, DispatchTeamRepository teamRepository,
                           WorkerRepository workerRepository, InsuranceRecordRepository insuranceRecordRepository,
                           InsuranceService insuranceService, SkillService skillService, RatingService ratingService,
                           AgriSettings settings) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.teamRepository = teamRepository;
        this.workerRepository = workerRepository;
        this.insuranceRecordRepository = insuranceRecordRepository;
        this.insuranceService = insuranceService;
        this.skillService = skillService;
        this.ratingService = ratingService;
        this.settings = settings;
    }

    /** 可參與派工的工班成員（外籍移工功能關閉時不含外籍工人） */
    private List<Worker> membersOf(String teamId) {
        return workerRepository.findByTeamId(teamId).stream().filter(settings::isWorkerAvailable).toList();
    }

    /** 訂單媒合總覽：工班排序＋（已分派或排第一的）工班出工名單建議 */
    public Map<String, Object> recommend(String orderId) {
        Order order = orderService.requireOrder(orderId);
        String skill = SkillService.CATEGORY_SKILL.get(order.getDispatchCategory());
        List<Map<String, Object>> teams = rankTeams(order, skill);
        String teamForCrew = teamRepository.existsById(String.valueOf(order.getAssignedTeamId()))
                ? order.getAssignedTeamId()
                : teams.stream().filter(t -> Boolean.TRUE.equals(t.get("eligible"))).map(t -> (String) t.get("teamId")).findFirst().orElse(null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", orderView(order, skill));
        m.put("teams", teams);
        m.put("crew", teamForCrew == null ? null : recommendCrew(order, skill, teamForCrew));
        return m;
    }

    public Map<String, Object> crewFor(String orderId, String teamId) {
        Order order = orderService.requireOrder(orderId);
        if (!teamRepository.existsById(teamId)) throw ApiException.notFound("找不到工班：" + teamId);
        return recommendCrew(order, SkillService.CATEGORY_SKILL.get(order.getDispatchCategory()), teamId);
    }

    /** 管理員確認派工：設定工班與出工名單 */
    public Order assign(String orderId, Object teamRaw, Object crewRaw) {
        Order order = orderService.requireOrder(orderId);
        if (!ASSIGNABLE_STATUS.contains(order.getStatus())) {
            throw ApiException.conflict("訂單狀態為「" + OrderService.statusLabel(order.getStatus()) + "」，無法調整派工");
        }
        String teamId = teamRaw == null ? "" : String.valueOf(teamRaw).trim();
        if (!teamRepository.existsById(teamId)) throw ApiException.badRequest("找不到工班：" + teamId);
        if (!(crewRaw instanceof Collection<?> c) || c.isEmpty()) throw ApiException.badRequest("crew 必須為至少 1 位工人的陣列");

        LinkedHashSet<String> crew = new LinkedHashSet<>();
        for (Object o : c) crew.add(String.valueOf(o).trim());
        Map<String, Worker> members = new HashMap<>();
        for (Worker w : workerRepository.findByTeamId(teamId)) members.put(w.getId(), w);
        LocalDate today = LocalDate.now();
        List<InsuranceRecord> records = insuranceRecordRepository.findByWorkerIdIn(members.keySet());
        List<String> problems = new ArrayList<>();
        Map<String, Order> busy = busyWorkers(order);
        for (String id : crew) {
            Worker w = members.get(id);
            if (w == null) throw ApiException.badRequest(id + " 不是 " + teamId + " 的成員");
            if (!settings.isWorkerAvailable(w)) {
                problems.add(displayName(w) + "：外籍移工服務暫停中，不可派工");
                continue;
            }
            String reason = ineligibleReason(w, records, today);
            if (reason == null && busy.containsKey(id)) reason = clashText(busy.get(id));
            if (reason != null) problems.add(displayName(w) + "：" + reason);
        }
        if (!problems.isEmpty()) throw ApiException.conflict("出工名單有不符合資格的工人：" + String.join("；", problems));

        order.setAssignedTeamId(teamId);
        order.setCrewWorkerIds(String.join(",", crew));
        return orderRepository.save(order);
    }

    // =========================================================================
    // 工班排序
    // =========================================================================

    private List<Map<String, Object>> rankTeams(Order order, String skill) {
        LocalDate today = LocalDate.now();
        int needed = Math.max(1, order.getSuggestedWorkers());
        String defaultTeam = defaultTeamFor(order.getDispatchCategory());
        Map<String, Map<String, Object>> ratings = new HashMap<>();
        for (Map<String, Object> r : ratingService.teamSummaries()) ratings.put((String) r.get("teamId"), r);

        Map<String, Order> busy = busyWorkers(order);
        Worker preferred = preferredWorker(order);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DispatchTeam t : teamRepository.findAll()) {
            List<Worker> members = membersOf(t.getId());
            List<InsuranceRecord> records = insuranceRecordRepository.findByWorkerIdIn(members.stream().map(Worker::getId).toList());
            int eligibleCount = 0, skilled = 0, senior = 0;
            for (Worker w : members) {
                if (ineligibleReason(w, records, today) == null && !busy.containsKey(w.getId())) eligibleCount++;
                Map<String, Object> s = skillOf(w, skill, today);
                if (s != null && Boolean.TRUE.equals(s.get("active"))) {
                    skilled++;
                    if ("senior".equals(s.get("level"))) senior++;
                }
            }
            boolean allInsured = members.stream().allMatch(w -> insuranceService.missingTypes(w, records, today).isEmpty());
            // 負載：此訂單有工期時算「工期重疊的訂單數」，否則退回「施工中訂單數」
            long load = orderRepository.findByAssignedTeamId(t.getId()).stream()
                    .filter(o -> !o.getId().equals(order.getId()))
                    .filter(o -> order.hasPeriod()
                            ? ACTIVE_STATUS.contains(o.getStatus()) && o.overlaps(order)
                            : OrderService.IN_PROGRESS.equals(o.getStatus()))
                    .count();
            Map<String, Object> rating = ratings.get(t.getId());
            Double avg = rating == null || rating.get("average") == null ? null : ((Number) rating.get("average")).doubleValue();

            List<String> blockers = new ArrayList<>();
            if (!allInsured) blockers.add("有成員未完整加保");
            if (eligibleCount < needed) blockers.add("合格人數 " + eligibleCount + " 人，少於需求 " + needed + " 人");
            List<String> reasons = new ArrayList<>();
            if (skill == null) reasons.add("此類別（" + order.getCategoryName() + "）以證照認定，不依經驗標籤排序");
            else reasons.add(SkillService.SKILL_LABELS.get(skill) + " " + skilled + " 人" + (senior > 0 ? "（資深 " + senior + "）" : ""));
            reasons.add(avg == null ? "工班尚無評價" : "工班評價 " + avg + " ★（" + rating.get("count") + " 則）");
            reasons.add(order.hasPeriod() ? "同期間另有 " + load + " 張訂單" : "目前施工中 " + load + " 張訂單（此訂單未排工期）");
            if (t.getId().equals(defaultTeam)) reasons.add("此類別預設工班");
            boolean hasPreferred = preferred != null && t.getId().equals(preferred.getTeamId());
            if (hasPreferred) reasons.add(0, "農場主指名工人 " + displayName(preferred) + " 在此工班");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("teamId", t.getId());
            m.put("teamName", t.getName());
            m.put("eligible", blockers.isEmpty());
            m.put("blockers", blockers);
            m.put("members", members.size());
            m.put("eligibleMembers", eligibleCount);
            m.put("skilled", skilled);
            m.put("senior", senior);
            m.put("averageRating", avg);
            m.put("activeOrders", load);
            m.put("isDefault", t.getId().equals(defaultTeam));
            m.put("hasPreferred", hasPreferred);
            m.put("reasons", reasons);
            out.add(m);
        }
        out.sort(Comparator
                .comparing((Map<String, Object> m) -> !(Boolean) m.get("eligible"))
                .thenComparing(m -> !(Boolean) m.get("hasPreferred"))
                .thenComparing(m -> -(Integer) m.get("skilled"))
                .thenComparing(m -> -(Integer) m.get("senior"))
                .thenComparing(m -> m.get("averageRating") == null ? 0.0 : -(Double) m.get("averageRating"))
                .thenComparing(m -> (Long) m.get("activeOrders"))
                .thenComparing(m -> !(Boolean) m.get("isDefault"))
                .thenComparing(m -> (String) m.get("teamId")));
        for (int i = 0; i < out.size(); i++) out.get(i).put("rank", i + 1);
        return out;
    }

    // =========================================================================
    // 出工名單
    // =========================================================================

    private Map<String, Object> recommendCrew(Order order, String skill, String teamId) {
        LocalDate today = LocalDate.now();
        int needed = Math.max(1, order.getSuggestedWorkers());
        List<Worker> members = membersOf(teamId);
        List<InsuranceRecord> records = insuranceRecordRepository.findByWorkerIdIn(members.stream().map(Worker::getId).toList());

        Map<String, Order> busy = busyWorkers(order);
        List<Map<String, Object>> eligible = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (Worker w : members) {
            Map<String, Object> c = candidate(w, skill, today);
            String reason = ineligibleReason(w, records, today);
            if (reason == null && busy.containsKey(w.getId())) reason = clashText(busy.get(w.getId()));
            if (reason == null) eligible.add(c);
            else {
                c.put("excludedReason", reason);
                excluded.add(c);
            }
        }
        eligible.sort(Comparator
                .comparing((Map<String, Object> c) -> -(Integer) c.get("levelRank"))
                .thenComparing(c -> (Double) c.get("needsImprovementRate"))
                .thenComparing(c -> -(Double) c.get("excellentRate"))
                .thenComparing(c -> -(Double) c.get("hours"))
                .thenComparing(c -> (String) c.get("workerId")));

        List<Map<String, Object>> picks = new ArrayList<>();
        String preferredId = order.getPreferredWorkerId();
        String preferredWarning = null;
        if (preferredId != null) {
            Optional<Map<String, Object>> p = eligible.stream().filter(c -> preferredId.equals(c.get("workerId"))).findFirst();
            if (p.isPresent()) {
                p.get().put("role", "農場主指名");
                picks.add(p.get());
            } else {
                String why = excluded.stream().filter(c -> preferredId.equals(c.get("workerId")))
                        .map(c -> (String) c.get("excludedReason")).findFirst()
                        .orElse("不在 " + teamId + " 工班或目前無法派工");
                preferredWarning = "農場主指名的 " + nameOr(order.getPreferredWorkerName(), preferredId) + " 無法排入：" + why
                        + "；請改派並告知農場主";
            }
        }
        // 必含一位領班（指名工人本身是領班時即由他帶隊）
        if (!picks.isEmpty() && Boolean.TRUE.equals(picks.get(0).get("leader"))) picks.get(0).put("role", "農場主指名・帶隊領班");
        else eligible.stream().filter(c -> Boolean.TRUE.equals(c.get("leader"))).findFirst().ifPresent(c -> {
            c.put("role", "帶隊領班");
            picks.add(c);
        });
        for (Map<String, Object> c : eligible) {
            if (picks.size() >= needed) break;
            if (!picks.contains(c)) picks.add(c);
        }
        // 培養名額：名單 ≥ 4 人且沒有培養中工人時，把最後一位非領班換成時數最多的培養中工人
        // （標籤暫停中的工人不算培養中，名額是保留給新進者累積經驗用的）
        boolean hasTrainee = picks.stream().anyMatch(MatchingService::isTrainee);
        if (needed >= TRAINEE_SLOT_MIN_CREW && !hasTrainee) {
            Optional<Map<String, Object>> trainee = eligible.stream()
                    .filter(c -> isTrainee(c) && !picks.contains(c))
                    .max(Comparator.comparing(c -> (Double) c.get("hours")));
            for (int i = picks.size() - 1; i >= 0 && trainee.isPresent(); i--) {
                String role = (String) picks.get(i).get("role");
                if (role == null) {
                    picks.set(i, trainee.get());
                    break;
                }
            }
        }
        for (Map<String, Object> c : picks) {
            c.putIfAbsent("role", Boolean.TRUE.equals(c.get("suspended")) ? "標籤暫停中"
                    : (Integer) c.get("levelRank") == 0 ? "培養名額" : (Integer) c.get("levelRank") == 2 ? "資深熟手" : "熟手");
            c.put("recommended", true);
        }
        Set<String> current = new HashSet<>(order.crewList());
        for (Map<String, Object> c : eligible) {
            c.putIfAbsent("recommended", false);
            c.put("onCurrentCrew", current.contains((String) c.get("workerId")));
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teamId", teamId);
        m.put("needed", needed);
        m.put("shortage", Math.max(0, needed - eligible.size()));
        m.put("currentCrew", order.crewList());
        m.put("periodChecked", order.hasPeriod());
        m.put("preferredWorkerId", preferredId);
        m.put("preferredWarning", preferredWarning);
        m.put("candidates", eligible);
        m.put("excluded", excluded);
        return m;
    }

    private Map<String, Object> candidate(Worker w, String skill, LocalDate today) {
        Map<String, Object> s = skillOf(w, skill, today);
        boolean active = s != null && Boolean.TRUE.equals(s.get("active"));
        int levelRank = !active ? 0 : "senior".equals(s.get("level")) ? 2 : 1;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("workerId", w.getId());
        c.put("name", displayName(w));
        c.put("leader", w.isLeader());
        c.put("levelRank", levelRank);
        c.put("suspended", s != null && Boolean.TRUE.equals(s.get("suspended")));
        c.put("levelLabel", s == null ? "—" : Boolean.TRUE.equals(s.get("suspended")) ? "標籤暫停中" : (String) s.get("levelLabel"));
        c.put("hours", s == null ? 0.0 : ((Number) s.get("hours")).doubleValue());
        c.put("excellentRate", s == null ? 0.0 : ((Number) s.get("excellentRate")).doubleValue());
        c.put("needsImprovementRate", s == null ? 0.0 : ((Number) s.get("needsImprovementRate")).doubleValue());
        return c;
    }

    /** 會佔用工人的訂單狀態 */
    private static final Set<String> ACTIVE_STATUS = Set.of(OrderService.PENDING, OrderService.CONFIRMED, OrderService.IN_PROGRESS);

    /** 工期與此訂單重疊、且已排入出工名單（或尚無名單但被指名）的工人 → 撞期的那張訂單（此訂單未排工期則不檢查） */
    Map<String, Order> busyWorkers(Order order) {
        Map<String, Order> busy = new HashMap<>();
        if (!order.hasPeriod()) return busy;
        for (Order o : orderRepository.findAll()) {
            if (Objects.equals(o.getId(), order.getId()) || !ACTIVE_STATUS.contains(o.getStatus()) || !o.overlaps(order)) continue;
            List<String> crew = o.crewList();
            for (String id : crew) busy.putIfAbsent(id, o);
            if (crew.isEmpty() && o.getPreferredWorkerId() != null) busy.putIfAbsent(o.getPreferredWorkerId(), o);
        }
        return busy;
    }

    /** 訂單的指名工人；未指名、已刪除或目前不開放派工（外籍移工下架）時回 null */
    private Worker preferredWorker(Order order) {
        if (order.getPreferredWorkerId() == null) return null;
        return workerRepository.findById(order.getPreferredWorkerId()).filter(settings::isWorkerAvailable).orElse(null);
    }

    private static String nameOr(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private static String clashText(Order o) {
        return "工期撞期：已排入 " + o.getId() + "（" + md(o.getStartDate()) + "–" + md(o.getEndDate())
                + (o.isHalfDay() ? "・" + Order.slotLabel(o.slot()) : "") + "）";
    }

    private static String md(LocalDate d) {
        return d.getMonthValue() + "/" + d.getDayOfMonth();
    }

    private static boolean isTrainee(Map<String, Object> c) {
        return (Integer) c.get("levelRank") == 0 && !Boolean.TRUE.equals(c.get("suspended"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> skillOf(Worker w, String skill, LocalDate today) {
        if (skill == null) return null;
        for (Map<String, Object> s : (List<Map<String, Object>>) skillService.profile(w, today).get("skills")) {
            if (skill.equals(s.get("key"))) return s;
        }
        return null;
    }

    /** 出工硬性條件：外籍工作許可有效、今日完整加保；符合回傳 null */
    String ineligibleReason(Worker w, List<InsuranceRecord> records, LocalDate today) {
        if (!CertificateService.permitValidOn(w, today)) return "工作許可已過期或未登錄";
        List<String> missing = insuranceService.missingTypes(w, records, today);
        if (!missing.isEmpty()) return "未完整加保（缺" + String.join("、", missing.stream().map(InsuranceService.TYPE_LABELS::get).toList()) + "）";
        return null;
    }

    @SuppressWarnings("unchecked")
    private String defaultTeamFor(String category) {
        for (Map<String, Object> c : orderService.getDispatchCategories()) {
            if (Objects.equals(c.get("id"), category)) return (String) c.get("suggestedTeam");
        }
        return null;
    }

    private Map<String, Object> orderView(Order o, String skill) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.getId());
        m.put("status", o.getStatus());
        m.put("statusLabel", OrderService.statusLabel(o.getStatus()));
        m.put("farmerName", o.getFarmerName());
        m.put("cropName", o.getCropName());
        m.put("categoryName", o.getCategoryName());
        m.put("skill", skill);
        m.put("skillLabel", skill == null ? null : SkillService.SKILL_LABELS.get(skill));
        m.put("suggestedWorkers", o.getSuggestedWorkers());
        m.put("startDate", o.getStartDate() == null ? null : o.getStartDate().toString());
        m.put("endDate", o.getEndDate() == null ? null : o.getEndDate().toString());
        m.put("timeSlot", o.slot());
        m.put("timeSlotLabel", Order.slotLabel(o.slot()));
        m.put("assignedTeamId", o.getAssignedTeamId());
        m.put("crew", o.crewList());
        m.put("preferredWorkerId", o.getPreferredWorkerId());
        m.put("preferredWorkerName", o.getPreferredWorkerName());
        return m;
    }

    public static String displayName(Worker w) {
        return w.getChineseName() != null && !w.getChineseName().isBlank() ? w.getChineseName() : w.getName();
    }
}
