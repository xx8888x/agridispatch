package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.Worker;
import com.agridispatch.entity.WorkerLeave;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.WorkerLeaveRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 工人本人的排班（GET /api/workers/{id}/schedule，工人行事曆用）
 *
 * 哪些訂單算「排給這位工人」：
 * - 訂單有出工名單（crewWorkerIds，由派工媒合設定）→ 名單內有本人才算，不論訂單掛在哪個工班；
 * - 沒有出工名單 → 訂單指派給本人所屬工班（assignedTeamId）就算。
 * 已取消、尚未排定工期（無 startDate）的訂單不列入。
 *
 * 只回現場需要的欄位：不含金額、定金、方案等計價資料，也不含農場主電話（現場聯絡一律找帶隊對口）。
 * 另附本人待審核與已核准的請假（leaves），行事曆標示用。
 *
 * 另提供本工班名冊（GET /api/workers/{id}/team-members，領班組員簽核名冊用）：
 * 只回同工班其他成員的工號、姓名、國籍、身分別（tier）、工作型態與是否為領班，不含證號、電話、薪資與保險資料。
 */
@Service
public class WorkerScheduleService {
    /** 查詢區間上限，避免一次撈整年以上 */
    public static final int MAX_RANGE_DAYS = 366;

    private final WorkerService workerService;
    private final OrderRepository orderRepository;
    private final DispatchTeamRepository teamRepository;
    private final WorkerLeaveRepository leaveRepository;

    public WorkerScheduleService(WorkerService workerService, OrderRepository orderRepository,
                                 DispatchTeamRepository teamRepository, WorkerLeaveRepository leaveRepository) {
        this.workerService = workerService;
        this.orderRepository = orderRepository;
        this.teamRepository = teamRepository;
        this.leaveRepository = leaveRepository;
    }

    /**
     * @param from 區間起日（含），null 表示不限
     * @param to   區間迄日（含），null 表示不限；與 from 同時給時須不早於 from 且不超過 MAX_RANGE_DAYS
     */
    public Map<String, Object> scheduleOf(String workerId, String from, String to) {
        Worker worker = workerService.requireWorker(workerId);
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        if (fromDate != null && toDate != null) {
            if (toDate.isBefore(fromDate)) throw ApiException.badRequest("to 不可早於 from");
            if (fromDate.plusDays(MAX_RANGE_DAYS).isBefore(toDate)) throw ApiException.badRequest("查詢區間不可超過 " + MAX_RANGE_DAYS + " 天");
        }

        String teamId = worker.getTeamId();
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, DispatchTeam> teams = new HashMap<>();
        for (Order o : orderRepository.findAll()) {
            if (OrderService.CANCELLED.equals(o.getStatus()) || o.getStartDate() == null) continue;
            String via = assignedVia(o, workerId, teamId);
            if (via == null) continue;
            LocalDate start = o.getStartDate();
            LocalDate end = o.getEndDate() != null ? o.getEndDate() : start;
            if (fromDate != null && end.isBefore(fromDate)) continue;
            if (toDate != null && start.isAfter(toDate)) continue;
            DispatchTeam team = o.getAssignedTeamId() == null ? null
                    : teams.computeIfAbsent(o.getAssignedTeamId(), id -> teamRepository.findById(id).orElse(null));
            items.add(item(o, start, end, via, team, workerId));
        }
        items.sort(Comparator.comparing((Map<String, Object> m) -> m.get("startDate").toString())
                .thenComparing(m -> m.get("orderId").toString()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workerId", worker.getId());
        data.put("teamId", teamId);
        DispatchTeam myTeam = teamId == null ? null : teamRepository.findById(teamId).orElse(null);
        data.put("team", myTeam == null ? null : teamInfo(myTeam));
        data.put("from", fromDate == null ? null : fromDate.toString());
        data.put("to", toDate == null ? null : toDate.toString());
        data.put("items", items);
        List<Map<String, Object>> leaves = new ArrayList<>();
        for (WorkerLeave l : leaveRepository.findByWorkerIdAndStatusIn(worker.getId(), List.of(WorkerLeave.PENDING, WorkerLeave.APPROVED))) {
            if (fromDate != null && l.getEndDate().isBefore(fromDate)) continue;
            if (toDate != null && l.getStartDate().isAfter(toDate)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("leaveType", l.getLeaveType());
            m.put("leaveTypeLabel", LeaveService.TYPE_LABELS.getOrDefault(l.getLeaveType(), l.getLeaveType()));
            m.put("startDate", l.getStartDate().toString());
            m.put("endDate", l.getEndDate().toString());
            m.put("timeSlot", l.getTimeSlot());
            m.put("status", l.getStatus());
            leaves.add(m);
        }
        leaves.sort(Comparator.comparing(m -> m.get("startDate").toString()));
        data.put("leaves", leaves);
        return data;
    }

    /** 本工班名冊（不含本人）；工人尚未編入工班時 members 為空陣列 */
    public Map<String, Object> teamRosterOf(String workerId) {
        Worker worker = workerService.requireWorker(workerId);
        String teamId = worker.getTeamId();
        List<Map<String, Object>> members = new ArrayList<>();
        if (teamId != null) {
            for (Worker w : workerService.getWorkersByTeam(teamId)) {
                if (w.getId().equals(worker.getId())) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", w.getId());
                m.put("name", w.getName());
                m.put("chineseName", w.getChineseName());
                m.put("nationality", w.getNationality());
                m.put("tier", w.getTier());
                m.put("employmentType", w.getEmploymentType());
                m.put("leader", w.isLeader());
                members.add(m);
            }
            members.sort(Comparator.comparing(m -> m.get("id").toString()));
        }
        DispatchTeam team = teamId == null ? null : teamRepository.findById(teamId).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workerId", worker.getId());
        data.put("teamId", teamId);
        data.put("teamName", team == null ? null : team.getName());
        data.put("members", members);
        return data;
    }

    /** 回傳 "crew"（出工名單有本人）、"team"（指派給本人工班且無出工名單），都不是回 null */
    static String assignedVia(Order o, String workerId, String teamId) {
        String crew = o.getCrewWorkerIds();
        if (crew != null && !crew.isBlank()) {
            for (String id : crew.split(",")) if (id.trim().equals(workerId)) return "crew";
            return null;
        }
        return teamId != null && teamId.equals(o.getAssignedTeamId()) ? "team" : null;
    }

    private static Map<String, Object> item(Order o, LocalDate start, LocalDate end, String via, DispatchTeam team, String workerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.getId());
        m.put("status", o.getStatus());
        m.put("statusLabel", OrderService.statusLabel(o.getStatus()));
        m.put("startDate", start.toString());
        m.put("endDate", end.toString());
        m.put("assignedVia", via);
        m.put("timeSlot", o.slot());
        m.put("timeSlotLabel", Order.slotLabel(o.slot()));
        m.put("preferredByFarmer", workerId.equals(o.getPreferredWorkerId()));   // 農場主指名本人（回頭客）
        m.put("teamId", o.getAssignedTeamId());
        m.put("cropName", o.getCropName());
        m.put("area", o.getArea());
        m.put("categoryName", o.getCategoryName());
        m.put("farmLocation", o.getFarmLocation());
        m.put("farmerName", o.getFarmerName());
        m.put("foremanName", o.getForemanName() != null ? o.getForemanName() : (team != null ? team.getLeader() : null));
        m.put("foremanPhone", o.getForemanPhone() != null ? o.getForemanPhone() : (team != null ? team.getLeaderPhone() : null));
        m.put("foremanNotes", o.getForemanNotes());
        m.put("vehicle", team != null ? team.getVehicle() : null);
        m.put("driver", team != null ? team.getDriver() : null);
        m.put("hedgeLocation", team != null ? team.getHedgeLocation() : null);
        m.put("hedgeTask", team != null ? team.getHedgeTask() : null);
        return m;
    }

    private static Map<String, Object> teamInfo(DispatchTeam t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("leader", t.getLeader());
        m.put("leaderPhone", t.getLeaderPhone());
        m.put("vehicle", t.getVehicle());
        m.put("driver", t.getDriver());
        m.put("normalLocation", t.getNormalLocation());
        m.put("normalTask", t.getNormalTask());
        m.put("hedgeLocation", t.getHedgeLocation());
        m.put("hedgeTask", t.getHedgeTask());
        return m;
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
