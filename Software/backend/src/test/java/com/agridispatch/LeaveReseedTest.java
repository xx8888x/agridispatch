package com.agridispatch;

import com.agridispatch.entity.WorkerLeave;
import com.agridispatch.repository.WorkerLeaveRepository;
import com.agridispatch.service.SeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * demo 重建種子（seedAll(true)）須先刪 worker_leaves：Oracle 的 worker_leaves.worker_id 有外鍵指向 workers，
 * 留著請假紀錄會擋下刪除工人（H2 由 JPA 建表沒有這條外鍵，所以這裡檢查請假紀錄確實被清掉）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_leave_reseed_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef"
})
class LeaveReseedTest {

    @Autowired SeedService seedService;
    @Autowired WorkerLeaveRepository leaveRepository;

    @Test
    void reseedClearsLeavesBeforeWorkers() {
        WorkerLeave l = new WorkerLeave();
        l.setId("LV-2099-001");
        l.setWorkerId("W026");
        l.setLeaveType("personal");
        l.setStartDate(LocalDate.now().plusDays(10));
        l.setEndDate(LocalDate.now().plusDays(10));
        leaveRepository.save(l);
        assertEquals(1, leaveRepository.count());

        seedService.seedAll(true);
        assertEquals(0, leaveRepository.count());
    }
}
