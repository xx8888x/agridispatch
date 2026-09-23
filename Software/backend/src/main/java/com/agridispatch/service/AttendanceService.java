package com.agridispatch.service;

import com.agridispatch.entity.Attendance;
import com.agridispatch.repository.AttendanceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;

    public AttendanceService(AttendanceRepository attendanceRepository) {
        this.attendanceRepository = attendanceRepository;
    }

    /**
     * 執行上班 GPS 打卡
     */
    public Attendance clockIn(String workerId, String location, Double gpsAccuracy) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new IllegalArgumentException("農工工號 (workerId) 不得為空！");
        }

        LocalDate today = LocalDate.now();
        // 檢查今日是否已打過上班卡且尚未下班
        Optional<Attendance> existing = attendanceRepository.findByWorkerIdAndWorkDateAndClockOutTimeIsNull(workerId, today);
        if (existing.isPresent()) {
            return existing.get(); // 已在出勤中
        }

        Attendance attendance = new Attendance(
                workerId,
                LocalDateTime.now(),
                location != null ? location : "桃園八德有機蔬菜園區 (24.9351°N, 121.2842°E)",
                gpsAccuracy != null ? gpsAccuracy : 3.5
        );
        return attendanceRepository.save(attendance);
    }

    /**
     * 執行下班簽核打卡
     */
    public Attendance clockOut(String workerId, String foremanSignature) {
        LocalDate today = LocalDate.now();
        Optional<Attendance> currentShift = attendanceRepository.findByWorkerIdAndWorkDateAndClockOutTimeIsNull(workerId, today);
        
        Attendance attendance;
        if (currentShift.isPresent()) {
            attendance = currentShift.get();
        } else {
            // 若忘記打上班卡，自動補建立
            attendance = new Attendance(
                    workerId,
                    LocalDateTime.now().minusHours(8).minusMinutes(30),
                    "屏東枋山愛文芒果產區 (22.2612°N, 120.6543°E)",
                    5.0
            );
        }

        attendance.completeShift(LocalDateTime.now(), foremanSignature != null ? foremanSignature : "林小祥 (帶隊小工頭)");
        return attendanceRepository.save(attendance);
    }

    /**
     * 查詢指定農工歷史打卡紀錄 (按日期倒序)
     */
    public List<Attendance> getAttendanceHistory(String workerId) {
        return attendanceRepository.findByWorkerIdOrderByWorkDateDesc(workerId);
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
