package com.agridispatch.repository;

import com.agridispatch.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 出勤打卡記錄資料存取層
 */
@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    /** 查詢指定農工的所有出勤記錄（按日期倒序） */
    List<Attendance> findByWorkerIdOrderByWorkDateDesc(String workerId);

    /** 查詢指定農工指定月份的出勤記錄 */
    @Query("SELECT a FROM Attendance a WHERE a.workerId = :workerId " +
           "AND YEAR(a.workDate) = :year AND MONTH(a.workDate) = :month " +
           "ORDER BY a.workDate DESC")
    List<Attendance> findByWorkerIdAndYearMonth(
            @Param("workerId") String workerId,
            @Param("year") int year,
            @Param("month") int month);

    /** 查詢指定農工今日是否已打上班卡（且尚未打下班卡） */
    Optional<Attendance> findByWorkerIdAndWorkDateAndClockOutTimeIsNull(
            String workerId, LocalDate workDate);

    /** 查詢指定農工今日所有打卡記錄 */
    List<Attendance> findByWorkerIdAndWorkDate(String workerId, LocalDate workDate);

    /** 查詢指定工班（teamId 需透過 Worker JOIN）的今日出勤人數 */
    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.workDate = :date AND a.status IN ('working','completed')")
    long countPresentWorkersByDate(@Param("date") LocalDate date);

    /** 查詢指定日期範圍的農工出勤統計 */
    @Query("SELECT a.workerId, COUNT(a), SUM(a.actualHours), SUM(a.overtimeHours) " +
           "FROM Attendance a " +
           "WHERE a.workerId = :workerId AND a.workDate BETWEEN :from AND :to " +
           "GROUP BY a.workerId")
    List<Object[]> summarizeByWorkerAndDateRange(
            @Param("workerId") String workerId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
