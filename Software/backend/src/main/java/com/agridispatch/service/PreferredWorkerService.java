package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.entity.InsuranceRecord;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.AttendanceRepository;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.InsuranceRecordRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 指名回頭工人（第一階段：小農「再約上次那位」）
 *
 * 可指名的對象：曾到本人訂單出工的工人——本人訂單（未取消）有出勤紀錄，或已完工訂單的出工名單上有他。
 * 只限合作過的人，避免農場主任意瀏覽工人名冊；外籍移工功能關閉時不列入。
 *
 * 給農場主看的欄位只有姓名、是否領班、工班、合作次數與最近一次日期；
 * 不能排班時只說「該期間已有排班」或「暫時無法排班」，不透露其他農場的訂單、保險或證件狀況。
 *
 * 指名不加價（指名費涉及 F2 定價，待財務確認後再議）；指名是優先排入而非保證，見 MatchingService。
 */
@Service
public class PreferredWorkerService {

    private static final int MAX_PERIOD_DAYS = 366;

    private final OrderRepository orderRepository;
    private final AttendanceRepository attendanceRepository;
    private final WorkerRepository workerRepository;
    private final DispatchTeamRepository teamRepository;
    private final InsuranceRecordRepository insuranceRecordRepository;
    private final MatchingService matchingService;
    private final AgriSettings settings;

    public PreferredWorkerService(OrderRepository orderRepository, AttendanceRepository attendanceRepository,
                                  WorkerRepository workerRepository, DispatchTeamRepository teamRepository,
                                  InsuranceRecordRepository insuranceRecordRepository, MatchingService matchingService,
                                  AgriSettings settings) {
        this.orderRepository = orderRepository;
        this.attendanceRepository = attendanceRepository;
        this.workerRepository = workerRepository;
        this.teamRepository = teamRepository;
        this.insuranceRecordRepository = insuranceRecordRepository;
        this.matchingService = matchingService;
        this.settings = settings;
    }

    /**
     * 農場主合作過的工人（合作次數多者在前），給了工期（start、end）時附上該期間能不能排班
     */
    public List<Map<String, Object>> pastWorkers(AuthContext ctx, String start, String end) {
        String farmerId = requireFarmerId(ctx);
        LocalDate from = parseDate(start, "start");
        LocalDate to = parseDate(end, "end");
        if (from != null && to == null) to = from;
        if (from == null && to != null) throw ApiException.badRequest("給 end 時須同時給 start");
        if (from != null) checkPeriod(from, to);

        Map<String, History> history = historyOf(farmerId);
        if (history.isEmpty()) return List.of();
        Map<String, Worker> workers = new HashMap<>();
        for (Worker w : workerRepository.findAllById(history.keySet())) {
            if (settings.isWorkerAvailable(w)) workers.put(w.getId(), w);
        }
        Map<String, Order> busy = from == null ? Map.of() : matchingService.busyWorkers(probe(from, to));
        List<InsuranceRecord> records = from == null ? List.of() : insuranceRecordRepository.findByWorkerIdIn(workers.keySet());
        LocalDate today = LocalDate.now();
        Map<String, String> teamNames = new HashMap<>();
        for (DispatchTeam t : teamRepository.findAll()) teamNames.put(t.getId(), t.getName());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Worker w : workers.values()) {
            History h = history.get(w.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workerId", w.getId());
            m.put("name", MatchingService.displayName(w));
            m.put("leader", w.isLeader());
            m.put("teamId", w.getTeamId());
            m.put("teamName", w.getTeamId() == null ? null : teamNames.get(w.getTeamId()));
            m.put("timesWorked", h.orderIds.size());
            m.put("lastWorkedOn", h.lastDate == null ? null : h.lastDate.toString());
            if (from == null) {
                m.put("available", null);
                m.put("unavailableReason", null);
            } else {
                String reason = busy.containsKey(w.getId()) ? "該期間已有排班"
                        : matchingService.ineligibleReason(w, records, today) != null ? "暫時無法排班" : null;
                m.put("available", reason == null);
                m.put("unavailableReason", reason);
            }
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> -(Integer) m.get("timesWorked"))
                .thenComparing(m -> m.get("lastWorkedOn") == null ? "" : (String) m.get("lastWorkedOn"), Comparator.reverseOrder())
                .thenComparing(m -> (String) m.get("workerId")));
        return out;
    }

    /**
     * 建單時驗證指名；未指名回 null。
     * @param farmerId 下單農場主；admin 代建時傳 null（不限合作過的工人，其餘檢查相同）
     */
    public Worker resolveForNewOrder(String farmerId, String workerId, LocalDate start, LocalDate end) {
        if (workerId == null || workerId.isBlank()) return null;
        String id = workerId.trim();
        Worker w = workerRepository.findById(id).filter(settings::isWorkerAvailable)
                .orElseThrow(() -> ApiException.badRequest("找不到指名的工人，請重新選擇"));
        if (farmerId != null && !historyOf(farmerId).containsKey(id)) {
            throw ApiException.badRequest("只能指名曾到您農場出工的工人");
        }
        if (start == null || end == null) throw ApiException.badRequest("指名工人須先選起工日期");
        checkPeriod(start, end);
        String name = MatchingService.displayName(w);
        if (matchingService.busyWorkers(probe(start, end)).containsKey(id)) {
            throw ApiException.conflict(name + " 在 " + start + " 起的工期已有排班，請改期或不指名");
        }
        LocalDate today = LocalDate.now();
        if (matchingService.ineligibleReason(w, insuranceRecordRepository.findByWorkerIdIn(List.of(id)), today) != null) {
            throw ApiException.conflict(name + " 暫時無法排班，請改選其他人或不指名");
        }
        return w;
    }

    /** 工人 → 在本農場主哪些訂單出過工、最近一次日期 */
    private Map<String, History> historyOf(String farmerId) {
        Map<String, History> out = new HashMap<>();
        for (Order o : orderRepository.findAll()) {
            if (!farmerId.equals(o.getFarmerId()) || OrderService.CANCELLED.equals(o.getStatus())) continue;
            for (Attendance a : attendanceRepository.findByOrderIdOrderByWorkDateAscClockInTimeAsc(o.getId())) {
                out.computeIfAbsent(a.getWorkerId(), k -> new History()).add(o.getId(), a.getWorkDate());
            }
            if (OrderService.COMPLETED.equals(o.getStatus())) {
                for (String id : o.crewList()) out.computeIfAbsent(id, k -> new History()).add(o.getId(), o.getEndDate());
            }
        }
        return out;
    }

    private static final class History {
        final Set<String> orderIds = new HashSet<>();
        LocalDate lastDate;

        void add(String orderId, LocalDate day) {
            orderIds.add(orderId);
            if (day != null && (lastDate == null || day.isAfter(lastDate))) lastDate = day;
        }
    }

    /** 只用來查撞期的虛擬訂單（不存檔） */
    private static Order probe(LocalDate start, LocalDate end) {
        Order o = new Order();
        o.setStartDate(start);
        o.setEndDate(end);
        return o;
    }

    private static void checkPeriod(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) throw ApiException.badRequest("完工日不可早於起工日");
        if (start.plusDays(MAX_PERIOD_DAYS).isBefore(end)) throw ApiException.badRequest("工期不可超過 " + MAX_PERIOD_DAYS + " 天");
    }

    private static String requireFarmerId(AuthContext ctx) {
        if (!ctx.isFarmer() || ctx.ref() == null || ctx.ref().isBlank()) throw ApiException.forbidden();
        return ctx.ref();
    }

    private static LocalDate parseDate(String v, String name) {
        if (v == null || v.isBlank()) return null;
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(name + " 須為 YYYY-MM-DD 日期");
        }
    }
}
