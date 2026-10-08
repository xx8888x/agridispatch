package com.agridispatch.repository;

import com.agridispatch.entity.Worker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkerRepository extends JpaRepository<Worker, String> {
    List<Worker> findByTeamId(String teamId);
    List<Worker> findByTier(String tier);

    // 證號 / 電話可能重複（例如舊資料本國工 ARC 皆為「本國身分證」），一律回傳 List，避免 IncorrectResultSize 例外
    List<Worker> findAllByArcNumber(String arcNumber);
    List<Worker> findAllByPassportNumber(String passportNumber);
    List<Worker> findAllByPhone(String phone);

    @Query("SELECT w.id FROM Worker w WHERE w.id LIKE CONCAT(:prefix, '%')")
    List<String> findIdsByPrefix(@Param("prefix") String prefix);

    /** Token 驗證用：只取 token 版本（農工不存在回 empty） */
    @Query("SELECT COALESCE(w.tokenVersion, 0) FROM Worker w WHERE w.id = :id")
    Optional<Integer> findTokenVersion(@Param("id") String id);
}
