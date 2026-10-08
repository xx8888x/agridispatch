package com.agridispatch;

import com.agridispatch.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正式環境啟動不寫入示範資料：agri.seed.demo-data 未設定（空值＝跟隨 demo 模式）且 demo 關閉時，
 * 資料庫只有管理員、作物費率與工時包 DM，沒有示範工班、工人、訂單、出勤、人才與示範通知；下單仍可運作。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_prod_seed_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.seed.demo-data=",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class ProductionSeedTest extends ApiTestSupport {

    @Autowired UserRepository userRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired DispatchTeamRepository teamRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired TalentRepository talentRepository;
    @Autowired InsuranceRecordRepository insuranceRecordRepository;
    @Autowired TeamRatingRepository teamRatingRepository;
    @Autowired LegalizeWorkerRepository legalizeWorkerRepository;
    @Autowired WeatherStateRepository weatherStateRepository;
    @Autowired CropRepository cropRepository;
    @Autowired BrochureRepository brochureRepository;

    @Test
    void productionStartupWritesNoDemoData() {
        assertEquals(1, userRepository.count(), "只有管理員帳號");
        assertTrue(userRepository.existsById("admin_tech"));
        assertEquals(0, workerRepository.count());
        assertEquals(0, orderRepository.count());
        assertEquals(0, teamRepository.count());
        assertEquals(0, attendanceRepository.count());
        assertEquals(0, talentRepository.count());
        assertEquals(0, insuranceRecordRepository.count());
        assertEquals(0, teamRatingRepository.count());
        assertEquals(0, legalizeWorkerRepository.count());
        assertEquals(0, weatherStateRepository.count());
        assertTrue(cropRepository.count() > 0, "作物費率為參考資料，正式環境也要有");
        assertTrue(brochureRepository.count() > 0, "工時包 DM 為產品定義，正式環境也要有");
    }

    @Test
    void productionStillWorksWithoutDemoData() throws Exception {
        String admin = login("admin_tech", "Admin#Pass2026");

        // 沒有示範通知
        assertEquals(0, json(call("GET", "/api/notifications", admin, null)).get("data").size());

        // 工班尚未建立：下單成功、工班為待分派（不指派不存在的建議工班）
        MvcResult created = call("POST", "/api/orders", admin,
                Map.of("farmerName", "首位農場主", "cropId", "mango", "area", 2.0, "plan", "hourly_flex"));
        assertEquals(201, created.getResponse().getStatus());
        JsonNode order = json(created).get("data");
        assertTrue(order.get("assignedTeamId").isNull());

        // 指定不存在的工班回 400
        assertEquals(400, call("POST", "/api/orders", admin, Map.of("farmerName", "x", "cropId", "mango", "area", 2.0,
                "plan", "hourly_flex", "assignedTeamId", "team_a")).getResponse().getStatus());

        // 天候總覽沒有資料表狀態時用內建預設值
        assertEquals(200, call("GET", "/api/dispatch/overview", null, null).getResponse().getStatus());
    }
}
