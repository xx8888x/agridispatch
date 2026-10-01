package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.AttendanceRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.WorkerRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * GPS 出勤打卡服務
 * - 上班：同一工人不得同時有兩筆未下班紀錄（以鎖 + 檢查確保）
 * - 下班：找該工人「最近一筆未下班」紀錄（可跨日），查無則拒絕，不再捏造工時
 */
@Service
public class AttendanceService {

    /** 單班超過此時數視為疑似忘記下班打卡，回應中附上提醒 */
    public static final double LONG_SHIFT_WARNING_HOURS = 16.0;

    private final AttendanceRepository attendanceRepository;
    private final WorkerRepository workerRepository;
    private final OrderRepository orderRepository;
    private final Object clockLock = new Object();

    public AttendanceService(AttendanceRepository attendanceRepository, WorkerRepository workerRepository,
                             OrderRepository orderRepository) {
        this.attendanceRepository = attendanceRepository;
        this.workerRepository = workerRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * 決定本次出勤所屬訂單：
     * - 有指定 orderId：須為進行中訂單，且工人屬於該訂單分派的工班，否則 400／409
     * - 未指定：工人所屬工班恰有一張進行中訂單時自動連結；有多張時，先取出工名單含此工人的那一張，
     *   再取今天落在工期內的那一張（恰一張才連結）；無法判定則不連結（null）
     */
    private String resolveOrderId(String workerId, String requestedOrderId) {
        Worker worker = workerRepository.findById(workerId).orElseThrow();
        if (requestedOrderId != null && !requestedOrderId.isBlank()) {
            Order order = orderRepository.findById(requestedOrderId.trim())
                    .orElseThrow(() -> ApiException.notFound("找不到訂單：" + requestedOrderId));
            if (!OrderService.IN_PROGRESS.equals(order.getStatus())) {
                throw ApiException.conflict("訂單 " + order.getId() + " 不在施工中，無法打卡");
            }
            if (worker.getTeamId() == null || !worker.getTeamId().equals(order.getAssignedTeamId())) {
                throw ApiException.badRequest("工人不屬於訂單 " + order.getId() + " 分派的工班");
            }
            if (!order.crewList().isEmpty() && !order.crewList().contains(workerId)) {
                throw ApiException.badRequest("工人不在訂單 " + order.getId() + " 的出工名單上");
            }
            return order.getId();
        }
        if (worker.getTeamId() == null) return null;
        List<Order> active = orderRepository.findByAssignedTeamId(worker.getTeamId()).stream()
                .filter(o -> OrderService.IN_PROGRESS.equals(o.getStatus())).toList();
        if (active.size() == 1) return active.get(0).getId();
        List<Order> onCrew = active.stream().filter(o -> o.crewList().contains(workerId)).toList();
        if (onCrew.size() == 1) return onCrew.get(0).getId();
        // 依工期判斷：今天在工期內、且出工名單未排除此工人的訂單恰有一張
        LocalDate today = LocalDate.now();
        List<Order> inPeriod = (onCrew.isEmpty() ? active : onCrew).stream()
                .filter(o -> o.crewList().isEmpty() || o.crewList().contains(workerId))
                .filter(o -> o.coversDate(today))
                .toList();
        return inPeriod.size() == 1 ? inPeriod.get(0).getId() : null;
    }

    private void requireWorker(String workerId) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw ApiException.badRequest("農工工號 (workerId) 不得為空！");
        }
        if (!workerRepository.existsById(workerId)) {
            throw ApiException.notFound("找不到該農工編號: " + workerId);
        }
    }

    /**
     * 執行上班 GPS 打卡
     * @throws ApiException 409：已有未下班的打卡紀錄
     */
    public Attendance clockIn(String workerId, String location, Double gpsAccuracy) {
        return clockIn(workerId, location, gpsAccuracy, null);
    }

    public Attendance clockIn(String workerId, String location, Double gpsAccuracy, String orderId) {
        requireWorker(workerId);
        if (location != null && location.length() > 200) {
            throw ApiException.badRequest("打卡位置描述過長（上限 200 字）");
        }
        if (gpsAccuracy != null && (gpsAccuracy.isNaN() || gpsAccuracy < 0 || gpsAccuracy > 100_000)) {
            throw ApiException.badRequest("GPS 精確度數值不合理");
        }
        synchronized (clockLock) {
            Optional<Attendance> open = attendanceRepository.findFirstByWorkerIdAndClockOutTimeIsNullOrderByClockInTimeDesc(workerId);
            if (open.isPresent()) {
                throw ApiException.conflict("已有未下班的打卡紀錄（" + open.get().getClockInTime().toLocalDate()
                        + "），請先完成下班打卡");
            }
            Attendance attendance = new Attendance(
                    workerId,
                    LocalDateTime.now(),
                    location != null && !location.isBlank() ? location : "未提供 GPS 位置描述",
                    gpsAccuracy
            );
            attendance.setOrderId(resolveOrderId(workerId, orderId));
            return attendanceRepository.save(attendance);
        }
    }

    /**
     * 執行下班簽核打卡：關閉最近一筆未下班紀錄（可跨日）
     * @throws ApiException 409：查無未下班的上班紀錄
     */
    public Attendance clockOut(String workerId, String foremanSignature) {
        requireWorker(workerId);
        if (foremanSignature != null && foremanSignature.length() > 50) {
            throw ApiException.badRequest("簽核姓名過長（上限 50 字）");
        }
        synchronized (clockLock) {
            Attendance attendance = attendanceRepository
                    .findFirstByWorkerIdAndClockOutTimeIsNullOrderByClockInTimeDesc(workerId)
                    .orElseThrow(() -> ApiException.conflict("找不到尚未下班的上班打卡紀錄，請先上班打卡"));
            LocalDateTime now = LocalDateTime.now();
            if (now.isBefore(attendance.getClockInTime())) {
                now = attendance.getClockInTime();
            }
            attendance.completeShift(now, foremanSignature != null && !foremanSignature.isBlank() ? foremanSignature : null);
            return attendanceRepository.save(attendance);
        }
    }

    public static boolean isLongShift(Attendance a) {
        return a.getActualHours() != null && a.getActualHours() > LONG_SHIFT_WARNING_HOURS;
    }

    /**
     * 查詢指定農工歷史打卡紀錄 (按日期倒序)
     */
    public List<Attendance> getAttendanceHistory(String workerId) {
        return attendanceRepository.findByWorkerIdOrderByWorkDateDesc(workerId);
    }

    public List<Attendance> getAttendanceByMonth(String workerId, int year, int month) {
        if (month < 1 || month > 12 || year < 2000 || year > 2100) {
            throw ApiException.badRequest("年月參數不合理");
        }
        return attendanceRepository.findByWorkerIdAndYearMonth(workerId, year, month);
    }

    /**
     * 查詢今日出勤打卡紀錄
     */
    public List<Attendance> getTodayAttendance(String workerId) {
        return attendanceRepository.findByWorkerIdAndWorkDate(workerId, LocalDate.now());
    }

    /**
     * 取得本日全工班出勤在線人數
     */
    public long getTodayPresentCount() {
        return attendanceRepository.countPresentWorkersByDate(LocalDate.now());
    }
}
