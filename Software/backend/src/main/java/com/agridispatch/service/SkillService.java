package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.*;
import com.agridispatch.repository.*;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 經驗標籤（熟手／資深熟手）自動判定與「履歷認證」徽章。
 *
 * 計算範圍：最近 24 個月、已下班簽核、且已連結訂單的出勤，依訂單派工類別歸入技能。
 * 熟手：時數 ≥ 80、訂單 ≥ 2、有評價訂單的工班平均 ≥ 4.0（皆未評價亦可）、領班「需加強」≤ 20%
 * 資深熟手：時數 ≥ 240、訂單 ≥ 4、至少 2 張有評價且平均 ≥ 4.3、領班已評核 ≥ 5 班且「優」≥ 50%、「需加強」≤ 20%
 * 暫停（任一，只看最近一次管理員複核之後的資料）：
 *   最近 3 張有評價訂單平均 < 3.0；工班評價標記「需加強訓練」≥ 2 張；最近 5 次領班評核中「需加強」≥ 2 次
 * 標籤只用於派工媒合優先順序，不得作為扣薪或解僱依據【待確認】；規則不使用國籍、性別、年齡。
 */
@Service
public class SkillService {

    public static final int WINDOW_MONTHS = 24;
    public static final double SKILLED_HOURS = 80;
    public static final int SKILLED_ORDERS = 2;
    public static final double SKILLED_MIN_RATING = 4.0;
    public static final double SENIOR_HOURS = 240;
    public static final int SENIOR_ORDERS = 4;
    public static final int SENIOR_RATED_ORDERS = 2;
    public static final double SENIOR_MIN_RATING = 4.3;
    public static final int SENIOR_MIN_EVALUATED = 5;
    public static final double SENIOR_EXCELLENT_RATE = 0.5;
    public static final double MAX_NEEDS_IMPROVEMENT_RATE = 0.2;
    public static final double SUSPEND_AVG_BELOW = 3.0;
    public static final int SUSPEND_TRAINING_TAGS = 2;
    public static final int SUSPEND_NEEDS_IN_LAST5 = 2;
    public static final int VERIFIED_NO_SUSPENSION_MONTHS = 6;
    static final String TRAINING_TAG = "需加強訓練";

    /** 派工類別 → 技能（無人機植保須證照，不給經驗標籤） */
    public static final Map<String, String> CATEGORY_SKILL = Map.of(
            "harvest_grading", "harvest", "typhoon_emergency", "harvest",
            "pruning_bagging", "pruning", "tillage_planting", "tillage", "cold_chain_pack", "packing");

    public static final Map<String, String> SKILL_LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("harvest", "💪 採收熟手");
        m.put("pruning", "✂️ 修剪套袋熟手");
        m.put("tillage", "🌱 整地定植熟手");
        m.put("packing", "📦 包裝熟手");
        SKILL_LABELS = Collections.unmodifiableMap(m);
    }

    private final WorkerRepository workerRepository;
    private final AttendanceRepository attendanceRepository;
    private final OrderRepository orderRepository;
    private final TeamRatingRepository ratingRepository;
    private final SkillReviewRepository reviewRepository;
    private final InsuranceRecordRepository insuranceRecordRepository;
    private final InsuranceService insuranceService;
    private final DispatchTeamRepository teamRepository;

    public SkillService(WorkerRepository workerRepository, AttendanceRepository attendanceRepository,
                        OrderRepository orderRepository, TeamRatingRepository ratingRepository,
                        SkillReviewRepository reviewRepository, InsuranceRecordRepository insuranceRecordRepository,
                        InsuranceService insuranceService, DispatchTeamRepository teamRepository) {
        this.workerRepository = workerRepository;
        this.attendanceRepository = attendanceRepository;
        this.orderRepository = orderRepository;
        this.ratingRepository = ratingRepository;
        this.reviewRepository = reviewRepository;
        this.insuranceRecordRepository = insuranceRecordRepository;
        this.insuranceService = insuranceService;
        this.teamRepository = teamRepository;
    }

    // =========================================================================
    // 對外查詢
    // =========================================================================

    /** 單一工人的技能進度與履歷認證（本人或 admin） */
    public Map<String, Object> workerProfile(AuthContext ctx, String workerId) {
        ctx.requireSelfOrAdmin(workerId);
        Worker w = workerRepository.findById(workerId).orElseThrow(() -> ApiException.notFound("找不到工人：" + workerId));
        return profile(w, LocalDate.now());
    }

    /** 全部工人摘要（admin） */
    public List<Map<String, Object>> allProfiles() {
        List<Worker> workers = workerRepository.findAll();
        workers.sort(Comparator.comparing(Worker::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Worker w : workers) out.add(profile(w, today));
        return out;
    }

    /** 工班標籤人數（任何登入者；不列姓名） */
    public Map<String, Object> teamSummary(String teamId) {
        if (!teamRepository.existsById(teamId)) throw ApiException.notFound("找不到工班：" + teamId);
        LocalDate today = LocalDate.now();
        List<Worker> members = workerRepository.findByTeamId(teamId);
        int verified = 0;
        Map<String, Map<String, Object>> skills = new LinkedHashMap<>();
        for (String key : SKILL_LABELS.keySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("label", SKILL_LABELS.get(key));
            s.put("skilled", 0);
            s.put("senior", 0);
            skills.put(key, s);
        }
        for (Worker w : members) {
            Map<String, Object> p = profile(w, today);
            if (Boolean.TRUE.equals(p.get("verified"))) verified++;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) p.get("skills");
            for (Map<String, Object> s : list) {
                if (!Boolean.TRUE.equals(s.get("active"))) continue;
                Map<String, Object> agg = skills.get((String) s.get("key"));
                String level = (String) s.get("level");
                agg.merge("skilled", 1, (a, b) -> (Integer) a + (Integer) b); // 資深也算在熟手人數內
                if ("senior".equals(level)) agg.merge("senior", 1, (a, b) -> (Integer) a + (Integer) b);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("total", members.size());
        out.put("verified", verified);
        out.put("skills", skills);
        return out;
    }

    /** 管理員複核後恢復標籤：此時間點之前的評價／評核不再觸發暫停 */
    public Map<String, Object> restore(AuthContext ctx, String workerId, String skillKey, Object noteRaw) {
        ctx.requireAdmin();
        if (!SKILL_LABELS.containsKey(skillKey)) throw ApiException.badRequest("不支援的技能：" + skillKey);
        Worker w = workerRepository.findById(workerId).orElseThrow(() -> ApiException.notFound("找不到工人：" + workerId));
        String note = noteRaw == null ? null : String.valueOf(noteRaw).replaceAll("\\p{Cntrl}", "").trim();
        if (note != null && note.length() > 200) throw ApiException.badRequest("備註最多 200 字");
        reviewRepository.save(new SkillReview(workerId, skillKey, LocalDateTime.now(), ctx.ref(), note));
        return profile(w, LocalDate.now());
    }

    // =========================================================================
    // 計算
    // =========================================================================

    private static final class Bucket {
        double hours;
        final Set<String> orders = new LinkedHashSet<>();
        /** 依最近出勤日排序的訂單（暫停規則看最近 3 張） */
        final Map<String, LocalDate> orderLastDay = new HashMap<>();
        /** 領班評核（由新到舊）：rating 與評核時間 */
        final List<Attendance> evaluated = new ArrayList<>();
    }

    Map<String, Object> profile(Worker w, LocalDate today) {
        LocalDate from = today.minusMonths(WINDOW_MONTHS);
        List<Attendance> shifts = attendanceRepository.findByWorkerIdOrderByWorkDateDesc(w.getId()).stream()
                .filter(a -> a.getClockOutTime() != null && a.getOrderId() != null && !a.getWorkDate().isBefore(from))
                .toList();
        Map<String, Order> orders = new HashMap<>();
        for (Order o : orderRepository.findAllById(shifts.stream().map(Attendance::getOrderId).distinct().toList())) {
            orders.put(o.getId(), o);
        }

        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (String key : SKILL_LABELS.keySet()) buckets.put(key, new Bucket());
        for (Attendance a : shifts) { // 由新到舊
            Order o = orders.get(a.getOrderId());
            if (o == null) continue;
            String skill = CATEGORY_SKILL.get(o.getDispatchCategory());
            if (skill == null) continue;
            Bucket b = buckets.get(skill);
            b.hours += a.getActualHours() == null ? 0 : a.getActualHours();
            b.orders.add(o.getId());
            b.orderLastDay.merge(o.getId(), a.getWorkDate(), (x, y) -> x.isAfter(y) ? x : y);
            if (a.getForemanRating() != null) b.evaluated.add(a);
        }

        Map<String, LocalDateTime> clearedAt = new HashMap<>();
        List<SkillReview> reviews = reviewRepository.findByWorkerIdOrderByClearedAtDesc(w.getId());
        for (SkillReview r : reviews) clearedAt.putIfAbsent(r.getSkillKey(), r.getClearedAt());

        List<Map<String, Object>> skills = new ArrayList<>();
        boolean anySuspended = false;
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
            Map<String, Object> s = evaluateSkill(e.getKey(), e.getValue(), clearedAt.get(e.getKey()));
            if (Boolean.TRUE.equals(s.get("suspended"))) anySuspended = true;
            skills.add(s);
        }

        // 履歷認證：工作許可有效（本國免）、今日完整加保、無暫停中標籤、近 6 個月未經暫停複核
        List<String> unverified = new ArrayList<>();
        if (!CertificateService.permitValidOn(w, today)) unverified.add("工作許可已過期或未登錄");
        List<String> missing = insuranceService.missingTypes(w, insuranceRecordRepository.findByWorkerIdOrderByEnrollDateDesc(w.getId()), today);
        if (!missing.isEmpty()) unverified.add("未完整加保（缺" + String.join("、", missing.stream().map(InsuranceService.TYPE_LABELS::get).toList()) + "）");
        if (anySuspended) unverified.add("有標籤暫停中，待合作社複核");
        LocalDateTime sixMonthsAgo = today.minusMonths(VERIFIED_NO_SUSPENSION_MONTHS).atStartOfDay();
        if (reviews.stream().anyMatch(r -> r.getClearedAt().isAfter(sixMonthsAgo))) unverified.add("近 6 個月曾有標籤暫停");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workerId", w.getId());
        m.put("name", w.getChineseName() != null && !w.getChineseName().isBlank() ? w.getChineseName() : w.getName());
        m.put("teamId", w.getTeamId());
        m.put("verified", unverified.isEmpty());
        m.put("unverifiedReasons", unverified);
        m.put("skills", skills);
        return m;
    }

    private Map<String, Object> evaluateSkill(String key, Bucket b, LocalDateTime cleared) {
        // 工班評價（每張訂單一筆）
        List<String> orderIdsByRecent = new ArrayList<>(b.orders);
        orderIdsByRecent.sort((x, y) -> b.orderLastDay.get(y).compareTo(b.orderLastDay.get(x)));
        List<TeamRating> ratings = new ArrayList<>();
        for (String id : orderIdsByRecent) ratingRepository.findByOrderId(id).ifPresent(ratings::add);
        double avg = ratings.stream().mapToInt(TeamRating::getScore).average().orElse(0);

        int evaluated = b.evaluated.size();
        long excellent = b.evaluated.stream().filter(a -> EvaluationService.EXCELLENT.equals(a.getForemanRating())).count();
        long needs = b.evaluated.stream().filter(a -> EvaluationService.NEEDS_IMPROVEMENT.equals(a.getForemanRating())).count();
        double needsRate = evaluated == 0 ? 0 : (double) needs / evaluated;
        double excellentRate = evaluated == 0 ? 0 : (double) excellent / evaluated;

        boolean skilled = b.hours >= SKILLED_HOURS && b.orders.size() >= SKILLED_ORDERS
                && (ratings.isEmpty() || avg >= SKILLED_MIN_RATING) && needsRate <= MAX_NEEDS_IMPROVEMENT_RATE;
        boolean senior = skilled && b.hours >= SENIOR_HOURS && b.orders.size() >= SENIOR_ORDERS
                && ratings.size() >= SENIOR_RATED_ORDERS && avg >= SENIOR_MIN_RATING
                && evaluated >= SENIOR_MIN_EVALUATED && excellentRate >= SENIOR_EXCELLENT_RATE;

        // 暫停規則：只看最近一次複核之後的資料
        List<String> suspendReasons = new ArrayList<>();
        List<TeamRating> recentRatings = ratings.stream()
                .filter(r -> cleared == null || r.getUpdatedAt() == null || r.getUpdatedAt().isAfter(cleared)).toList();
        if (recentRatings.size() >= 3) {
            double last3 = recentRatings.subList(0, 3).stream().mapToInt(TeamRating::getScore).average().orElse(5);
            if (last3 < SUSPEND_AVG_BELOW) suspendReasons.add("最近 3 張訂單工班評價平均 " + round1(last3) + " 星（低於 3.0）");
        }
        long trainingTags = recentRatings.stream().filter(r -> r.getTags() != null && r.getTags().contains(TRAINING_TAG)).count();
        if (trainingTags >= SUSPEND_TRAINING_TAGS) suspendReasons.add("工班評價 " + trainingTags + " 次標記「需加強訓練」");
        List<Attendance> recentEvals = b.evaluated.stream()
                .filter(a -> cleared == null || a.getForemanRatedAt() == null || a.getForemanRatedAt().isAfter(cleared))
                .sorted(Comparator.comparing(Attendance::getWorkDate).reversed())
                .limit(5).toList();
        long needsLast5 = recentEvals.stream().filter(a -> EvaluationService.NEEDS_IMPROVEMENT.equals(a.getForemanRating())).count();
        if (needsLast5 >= SUSPEND_NEEDS_IN_LAST5) suspendReasons.add("最近 5 次領班評核有 " + needsLast5 + " 次「需加強」");

        String level = senior ? "senior" : skilled ? "skilled" : "none";
        boolean suspended = !"none".equals(level) && !suspendReasons.isEmpty();

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("label", SKILL_LABELS.get(key));
        s.put("level", level);
        s.put("levelLabel", senior ? "資深熟手" : skilled ? "熟手" : "培養中");
        s.put("active", !"none".equals(level) && !suspended);
        s.put("suspended", suspended);
        s.put("suspendReasons", suspended ? suspendReasons : List.of());
        s.put("hours", round1(b.hours));
        s.put("orders", b.orders.size());
        s.put("ratedOrders", ratings.size());
        s.put("averageRating", ratings.isEmpty() ? null : round1(avg));
        s.put("evaluatedShifts", evaluated);
        s.put("excellentRate", round2(excellentRate));
        s.put("needsImprovementRate", round2(needsRate));
        s.put("next", nextSteps(skilled, b, ratings, avg, evaluated, excellentRate, needsRate));
        return s;
    }

    /** 還差什麼才能升級（給工人看的進度說明） */
    private static List<String> nextSteps(boolean skilled, Bucket b, List<TeamRating> ratings, double avg,
                                          int evaluated, double excellentRate, double needsRate) {
        List<String> next = new ArrayList<>();
        if (!skilled) {
            if (b.hours < SKILLED_HOURS) next.add("時數 " + round1(b.hours) + "／" + (int) SKILLED_HOURS + " 小時");
            if (b.orders.size() < SKILLED_ORDERS) next.add("訂單 " + b.orders.size() + "／" + SKILLED_ORDERS + " 張");
            if (!ratings.isEmpty() && avg < SKILLED_MIN_RATING) next.add("工班評價平均 " + round1(avg) + "（需 ≥ 4.0）");
            if (needsRate > MAX_NEEDS_IMPROVEMENT_RATE) next.add("領班「需加強」比例 " + pct(needsRate) + "（需 ≤ 20%）");
            return next;
        }
        if (b.hours < SENIOR_HOURS) next.add("時數 " + round1(b.hours) + "／" + (int) SENIOR_HOURS + " 小時");
        if (b.orders.size() < SENIOR_ORDERS) next.add("訂單 " + b.orders.size() + "／" + SENIOR_ORDERS + " 張");
        if (ratings.size() < SENIOR_RATED_ORDERS) next.add("有評價訂單 " + ratings.size() + "／" + SENIOR_RATED_ORDERS + " 張");
        else if (avg < SENIOR_MIN_RATING) next.add("工班評價平均 " + round1(avg) + "（需 ≥ 4.3）");
        if (evaluated < SENIOR_MIN_EVALUATED) next.add("領班評核 " + evaluated + "／" + SENIOR_MIN_EVALUATED + " 班");
        else if (excellentRate < SENIOR_EXCELLENT_RATE) next.add("領班「優」比例 " + pct(excellentRate) + "（需 ≥ 50%）");
        return next;
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
    private static String pct(double v) { return Math.round(v * 100) + "%"; }
}
