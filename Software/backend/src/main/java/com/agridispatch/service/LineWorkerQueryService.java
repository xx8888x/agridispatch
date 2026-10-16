package com.agridispatch.service;

import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.DispatchTeamRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * LINE 工作者帳號的本人查詢（POST /api/line/internal/query，channel=worker；規格 05 第 4.2 節）
 *   task    W-01 今日任務：本人今天的排班（WorkerScheduleService 同一套規則）；今天沒有時附 14 天內下一筆；
 *           組長另附工班今日打卡摘要
 *   weather W-03 天候與集合：目前天候＋本人今天各班次的集合地點、作業、車號與司機（工班改室內時用避險地點）
 *   salary  W-04 薪資：本月薪資試算（WorkerService.calculatePayroll，標示 simulated）
 *
 * 回傳的是資料，不是 LINE 訊息：由 LINE Bot 依使用者語言組卡片（與推播 NotifyMessages 相同分工）。
 * 不回訂單金額、費率與農場主資訊（規格第拾章第 3 點）；天候只回天氣與警戒等級，不回後台訊息（內含營收數字）。
 */
@Service
public class LineWorkerQueryService {
    /** 今天沒有任務時，往後找下一筆的天數 */
    static final int NEXT_TASK_DAYS = 14;

    private final WorkerService workerService;
    private final WorkerScheduleService scheduleService;
    private final AttendanceService attendanceService;
    private final DispatchService dispatchService;
    private final DispatchTeamRepository teamRepository;
    private final AgriSettings settings;

    public LineWorkerQueryService(WorkerService workerService, WorkerScheduleService scheduleService,
                                  AttendanceService attendanceService, DispatchService dispatchService,
                                  DispatchTeamRepository teamRepository, AgriSettings settings) {
        this.workerService = workerService;
        this.scheduleService = scheduleService;
        this.attendanceService = attendanceService;
        this.dispatchService = dispatchService;
        this.teamRepository = teamRepository;
        this.settings = settings;
    }

    /** 查不到工人、外籍移工功能關閉時的外籍工人、不支援的動作 → 回傳空 Optional */
    public Optional<Map<String, Object>> query(String workerId, String action) {
        Worker w = workerId == null ? null : workerService.getWorkerById(workerId).orElse(null);
        if (w == null || !settings.isWorkerAvailable(w) || action == null) return Optional.empty();
        return switch (action) {
            case "task" -> Optional.of(task(w));
            case "weather" -> Optional.of(weather(w));
            case "salary" -> Optional.of(salary(w));
            default -> Optional.empty();
        };
    }

    private Map<String, Object> task(Worker w) {
        LocalDate today = LocalDate.now();
        Map<String, Object> d = base("task", w, today);
        d.put("machinery", "tier3_turnkey".equals(w.getTier()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> it : scheduleOn(w, today, today)) {
            Map<String, Object> m = pick(it, "orderId", "statusLabel", "timeSlotLabel", "cropName", "categoryName", "area",
                    "foremanName", "foremanPhone", "foremanNotes", "preferredByFarmer");
            m.put("place", it.get("farmLocation"));
            items.add(m);
        }
        d.put("items", items);
        Map<String, Object> next = null;
        if (items.isEmpty()) {
            List<Map<String, Object>> later = scheduleOn(w, today.plusDays(1), today.plusDays(NEXT_TASK_DAYS));
            if (!later.isEmpty()) {
                Map<String, Object> it = later.get(0);
                next = pick(it, "startDate", "timeSlotLabel", "cropName", "categoryName");
                next.put("place", it.get("farmLocation"));
            }
        }
        d.put("next", next);
        d.put("crew", w.isLeader() ? crew(w) : null);
        return d;
    }

    /** 組長的工班今日打卡摘要：人數、已打卡人數、尚未打卡者姓名（不含證號、電話、薪資） */
    private Map<String, Object> crew(Worker leader) {
        if (leader.getTeamId() == null) return null;
        int total = 0, present = 0;
        List<String> absent = new ArrayList<>();
        List<Worker> members = new ArrayList<>(workerService.getWorkersByTeam(leader.getTeamId()));
        members.sort(Comparator.comparing(Worker::getId));
        for (Worker m : members) {
            if (!settings.isWorkerAvailable(m)) continue;
            total++;
            boolean in = attendanceService.getTodayAttendance(m.getId()).stream().anyMatch(a -> a.getClockInTime() != null);
            if (in) present++;
            else absent.add(displayName(m));
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("teamName", teamRepository.findById(leader.getTeamId()).map(DispatchTeam::getName).orElse(leader.getTeamId()));
        c.put("total", total);
        c.put("present", present);
        c.put("absentNames", absent);
        return c;
    }

    private Map<String, Object> weather(Worker w) {
        LocalDate today = LocalDate.now();
        Map<String, Object> d = base("weather", w, today);
        Map<String, Object> state = dispatchService.getWeatherStateSnapshot();
        d.put("weather", state.get("currentWeather"));
        d.put("alertLevel", state.get("alertLevel"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> it : scheduleOn(w, today, today)) {
            DispatchTeam team = it.get("teamId") == null ? null : teamRepository.findById(it.get("teamId").toString()).orElse(null);
            Map<String, Object> m = pick(it, "orderId", "timeSlotLabel", "vehicle", "driver", "foremanName", "foremanPhone");
            m.put("place", firstText(it.get("farmLocation"), team == null ? null : team.getNormalLocation()));
            m.put("work", firstText(it.get("categoryName"), team == null ? null : team.getNormalTask()));
            items.add(m);
        }
        d.put("items", items);
        return d;
    }

    private Map<String, Object> salary(Worker w) {
        Map<String, Object> payroll = workerService.calculatePayroll(w.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> cur = (Map<String, Object>) payroll.get("currentMonth");
        Map<String, Object> d = base("salary", w, LocalDate.now());
        // 月份與入帳日只給日期（calculatePayroll 的字串帶中文說明，LINE Bot 另依語言加標籤）
        d.put("month", YearMonth.now().toString());
        d.put("payDate", String.valueOf(cur.get("payDate")).split(" ")[0]);
        d.putAll(pick(cur, "workedHours", "overtimeHours", "baseSalary", "agriSubsidy",
                "transportHeatAllowance", "overtimePay", "laborInsuranceDeduction", "healthInsuranceDeduction",
                "grossPay", "totalDeductions", "netPay"));
        d.put("simulated", true);
        return d;
    }

    private List<Map<String, Object>> scheduleOn(Worker w, LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) scheduleService
                .scheduleOf(w.getId(), from.toString(), to.toString()).get("items");
        return items;
    }

    private static Map<String, Object> base(String kind, Worker w, LocalDate date) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", kind);
        d.put("date", date.toString());
        d.put("workerId", w.getId());
        d.put("workerName", displayName(w));
        return d;
    }

    private static String displayName(Worker w) {
        return w.getChineseName() != null && !w.getChineseName().isBlank() ? w.getChineseName() : w.getName();
    }

    private static Map<String, Object> pick(Map<String, Object> src, String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, src.get(k));
        return m;
    }

    private static String firstText(Object a, Object b) {
        if (a != null && !a.toString().isBlank()) return a.toString();
        return b == null || b.toString().isBlank() ? null : b.toString();
    }
}
