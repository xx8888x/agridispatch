package com.agridispatch.service;

import com.agridispatch.entity.User;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.repository.WorkerRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final WorkerRepository workerRepository;

    public AuthService(UserRepository userRepository, WorkerRepository workerRepository) {
        this.userRepository = userRepository;
        this.workerRepository = workerRepository;
    }

    public Optional<Map<String, Object>> authenticate(String identifier, String password) {
        if (identifier == null || password == null) return Optional.empty();
        String id = identifier.trim();

        // 1. Direct match in users table
        Optional<User> userOpt = userRepository.findById(id)
                .filter(u -> u.getPassword().equals(password));

        if (userOpt.isPresent()) {
            User u = userOpt.get();
            Map<String, Object> result = new HashMap<>();
            result.put("username", u.getUsername());
            result.put("name", u.getName());
            result.put("role", u.getRole());
            result.put("refId", u.getRefId());
            result.put("roleDesc", u.getRoleDesc());

            // If refId points to a worker, attach worker info
            if (u.getRefId() != null && workerRepository.existsById(u.getRefId())) {
                workerRepository.findById(u.getRefId()).ifPresent(w -> result.put("workerProfile", w));
            }
            return Optional.of(result);
        }

        // 2. Farmer phone or alias
        if (("0912-345-678".equals(id) || "farmer".equalsIgnoreCase(id) || "farmer_yilan".equalsIgnoreCase(id)) && "123456".equals(password)) {
            Map<String, Object> result = new HashMap<>();
            result.put("username", "farmer");
            result.put("name", "陳建龍 (阿龍班長)");
            result.put("role", "farmer");
            result.put("refId", "F001");
            result.put("roleDesc", "八德有機蔬菜產銷班負責人");
            result.put("phone", "0912-345-678");
            result.put("farmLocation", "桃園市八德區興豐路有機蔬菜專區");
            return Optional.of(result);
        }

        // 2b. Cooperative Labor Worker (合作社在籍農業勞務員 - 三軌獨立身分)
        if (("0975-123-456".equals(id) || "worker_labor".equalsIgnoreCase(id) || "W030".equalsIgnoreCase(id) || "labor".equalsIgnoreCase(id)) && "123456".equals(password)) {
            Map<String, Object> result = new HashMap<>();
            result.put("username", "W030");
            result.put("name", "張志明 (合作社農業勞務員)");
            result.put("role", "worker_local");
            result.put("subRole", "labor");
            result.put("refId", "W030");
            result.put("roleDesc", "合作社在籍農業勞務員 (月保障底薪 $28,590 + 農業部就業獎勵金 $8,800)");
            result.put("phone", "0975-123-456");
            return Optional.of(result);
        }

        // 3. Match against Worker repository (by ID, ARC, Passport, or Phone)
        Optional<Worker> workerOpt = workerRepository.findById(id);
        if (workerOpt.isEmpty()) {
            workerOpt = workerRepository.findByArcNumber(id);
        }
        if (workerOpt.isEmpty()) {
            workerOpt = workerRepository.findByPassportNumber(id);
        }
        if (workerOpt.isEmpty()) {
            workerOpt = workerRepository.findByPhone(id);
        }

        if (workerOpt.isPresent()) {
            Worker w = workerOpt.get();
            // Default password for workers is 123456
            if ("123456".equals(password)) {
                boolean isForeign = "tier1_foreign".equals(w.getTier());
                String role = isForeign ? "worker_foreign" : "worker_local";
                String roleDesc = isForeign ? "外籍特許外展農工 (Tier 1)" : ("tier3_turnkey".equals(w.getTier()) ? "本國專業機耕師 (Tier 3)" : "本國彈性兼職農工 (Tier 2)");

                Map<String, Object> result = new HashMap<>();
                result.put("username", w.getId());
                result.put("name", w.getChineseName() + " (" + w.getName() + ")");
                result.put("role", role);
                result.put("refId", w.getId());
                result.put("roleDesc", roleDesc);
                result.put("workerProfile", w);
                return Optional.of(result);
            }
        }

        return Optional.empty();
    }

    public Map<String, List<Map<String, String>>> getQuickProfiles() {
        return Map.of(
            "farmers", List.of(
                Map.of("label", "陳建龍 (阿龍班長) - 八德有機蔬菜班", "username", "farmer", "password", "123456", "note", "契作面積 5.0 分地・早鳥 9 折預約中"),
                Map.of("label", "李春發 (春發伯) - 宜蘭三星青蔥專班", "username", "farmer_yilan", "password", "123456", "note", "契作面積 8.2 分地・跨區調度專線")
            ),
            "foreignWorkers", List.of(
                Map.of("label", "🇻🇳 阮文雄 (Nguyễn Văn Hùng) - 工班A組長", "id", "W001", "arc", "AD88123451", "passport", "C1234561", "password", "123456", "nationality", "越南"),
                Map.of("label", "🇮🇩 Budi Santoso - 工班B組長", "id", "W006", "arc", "AD77234561", "passport", "X8812301", "password", "123456", "nationality", "印尼"),
                Map.of("label", "🇹🇭 Somchai Prasert (阿差) - 工班D組", "id", "W020", "arc", "AD66345671", "passport", "T5512301", "password", "123456", "nationality", "泰國"),
                Map.of("label", "🇵🇭 Maria Santos (瑪麗亞) - 工班B組", "id", "W023", "arc", "AD55456781", "passport", "P4412301", "password", "123456", "nationality", "菲律賓")
            ),
            "localWorkers", List.of(
                Map.of("label", "🇹🇼 林小祥 (外展帶隊領班) - 帶領6人外勞小工頭", "id", "W026", "phone", "0918-668-999", "password", "123456", "tier", "Tier 2 帶隊領班"),
                Map.of("label", "🇹🇼 陳建宏 (專業機耕/飛手) - 大疆T50巡檢代噴", "id", "W012", "phone", "0988-333-444", "password", "123456", "tier", "Tier 3 專業代耕"),
                Map.of("label", "🇹🇼 林志成 (資深機耕師) - 80馬力曳引機手", "id", "W025", "phone", "0933-555-666", "password", "123456", "tier", "Tier 3 專業代耕")
            )
        );
    }
}
