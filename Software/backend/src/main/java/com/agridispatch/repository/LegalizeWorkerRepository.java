package com.agridispatch.repository;

import com.agridispatch.entity.LegalizeWorker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LegalizeWorkerRepository extends JpaRepository<LegalizeWorker, String> {
    List<LegalizeWorker> findByFarmerIdOrderByCreatedAtAsc(String farmerId);
    List<LegalizeWorker> findAllByOrderByFarmerIdAscCreatedAtAsc();
    void deleteByFarmerId(String farmerId);

    /** 取出指定前綴的編號（產生不重複序號用） */
    @Query("SELECT w.id FROM LegalizeWorker w WHERE w.id LIKE CONCAT(:prefix, '%')")
    List<String> findIdsByPrefix(@Param("prefix") String prefix);
}
