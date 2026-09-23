package com.agridispatch.service;

import com.agridispatch.entity.Crop;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.CropRepository;
import com.agridispatch.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.*;

@Service
@Transactional
public class OrderService {
    private final OrderRepository orderRepository;
    private final CropRepository cropRepository;

    public OrderService(OrderRepository orderRepository, CropRepository cropRepository) {
        this.orderRepository = orderRepository;
        this.cropRepository = cropRepository;
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    public Optional<Order> getOrderById(String id) {
        return orderRepository.findById(id);
    }

    public List<Map<String, Object>> getDispatchCategories() {
        return List.of(
            Map.of(
                "id", "harvest_grading",
                "name", "【採收分級】田間採收與外銷分級",
                "icon", "🥬",
                "description", "蔬菜採收、果實留蒂剪摘、田間初級篩選去老葉、標準規格分級裝箱。",
                "suggestedTeam", "team_a",
                "suggestedTeamName", "工班 A 組 (桃園溫室蔬菜組)",
                "rateNote", "標準牌價 (早鳥 9 折)"
            ),
            Map.of(
                "id", "tillage_planting",
                "name", "【整地種植】曳引機深耕與定植",
                "icon", "🌱",
                "description", "大型曳引機深耕碎土、起壟作畦、鋪設抑草蓆、菜苗定植、水稻插秧。",
                "suggestedTeam", "team_c",
                "suggestedTeamName", "工班 C 組 (機耕無人機隊)",
                "rateNote", "專業機耕或時薪 $310"
            ),
            Map.of(
                "id", "pruning_bagging",
                "name", "【田間管理】修枝疏果與引蔓套袋",
                "icon", "✂️",
                "description", "果樹修枝剪梢、疏花疏果、立棚架引蔓、芒果/水梨/葡萄防蟲套袋。",
                "suggestedTeam", "team_b",
                "suggestedTeamName", "工班 B 組 (跨區專線組)",
                "rateNote", "精細人工工時"
            ),
            Map.of(
                "id", "drone_pest_control",
                "name", "【科技植保】無人機精準噴藥施肥",
                "icon", "🛸",
                "description", "專業植保機低空定點噴藥、粒肥精準拋撒、土壤改良劑代噴，附 GPS 航跡報告。",
                "suggestedTeam", "team_c",
                "suggestedTeamName", "工班 C 組 (機耕無人機隊)",
                "rateNote", "成果代耕 ($800/分地)"
            ),
            Map.of(
                "id", "cold_chain_pack",
                "name", "【冷鏈包裝】室內預冷與生鮮截切",
                "icon", "❄️",
                "description", "蔬果預冷入庫、自動光波秤重、生鮮超市小包裝、外銷標準貼標封箱。",
                "suggestedTeam", "team_d",
                "suggestedTeamName", "工班 D 組 (冷鏈常態組)",
                "rateNote", "雨天避險替代作業"
            ),
            Map.of(
                "id", "typhoon_emergency",
                "name", "【緊急救火】防颱搶收 24H 馳援",
                "icon", "🚨",
                "description", "颱風豪雨前夕 24H 徹夜搶收、防寒防霜覆蓋、農田清溝排水，緊急跨區馳援。",
                "suggestedTeam", "team_b",
                "suggestedTeamName", "全工班跨區緊急馳援",
                "rateNote", "緊急救火工時 ($360/hr)"
            )
        );
    }

    public Map<String, Object> calculateOrder(String cropId, double area, String plan, String zone) {
        return calculateOrder(cropId, area, plan, zone, "harvest_grading");
    }

    public Map<String, Object> calculateOrder(String cropId, double area, String plan, String zone, String dispatchCategory) {
        if (dispatchCategory == null || dispatchCategory.isBlank()) {
            dispatchCategory = "harvest_grading";
        }

        Crop crop = cropRepository.findById(cropId).orElseGet(() -> {
            List<Crop> all = cropRepository.findAll();
            return all.isEmpty() ? null : all.get(0);
        });

        int baseHours = (crop != null) ? crop.getBaseHoursPerFen() : 20;
        int workersPerFen = (crop != null) ? crop.getWorkersPerFen() : 3;
        int unitRate = (crop != null) ? crop.getRate() : 290;

        // 依據派工類別調整費率與工班
        String categoryName = "【採收分級】田間採收與外銷分級";
        String suggestedTeam = "team_a";
        String suggestedTeamName = "工班 A 組 (桃園溫室蔬菜組)";

        switch (dispatchCategory) {
            case "tillage_planting":
                categoryName = "【整地種植】曳引機深耕與定植";
                suggestedTeam = "team_c";
                suggestedTeamName = "工班 C 組 (機耕無人機隊)";
                break;
            case "pruning_bagging":
                categoryName = "【田間管理】修枝疏果與引蔓套袋";
                suggestedTeam = "team_b";
                suggestedTeamName = "工班 B 組 (跨區專線組)";
                break;
            case "drone_pest_control":
                categoryName = "【科技植保】無人機精準噴藥施肥";
                suggestedTeam = "team_c";
                suggestedTeamName = "工班 C 組 (機耕無人機隊)";
                break;
            case "cold_chain_pack":
                categoryName = "【冷鏈包裝】室內預冷與生鮮截切";
                suggestedTeam = "team_d";
                suggestedTeamName = "工班 D 組 (冷鏈常態組)";
                break;
            case "typhoon_emergency":
                categoryName = "【緊急救火】防颱搶收 24H 馳援";
                suggestedTeam = "team_b";
                suggestedTeamName = "全工班跨區緊急馳援";
                unitRate = Math.max(unitRate, 360); // 緊急救火工時費率
                break;
            default:
                break;
        }

        int totalHours = (int) Math.round(baseHours * area);
        int suggestedWorkers = Math.max(2, (int) Math.round(workersPerFen * (area > 3 ? 1.5 : 1)));
        double estDays = Math.round((totalHours / (suggestedWorkers * 8.0)) * 10.0) / 10.0;

        double discount = 1.0;
        String planName = "【Tier 1】產季早鳥工時包 (9折・保證出工)";
        int surcharge = "cross_region".equals(zone) ? 1200 : 0;

        if ("early_bird".equals(plan)) {
            discount = 0.90;
            planName = "【Tier 1】核心外展移工早鳥包 (預售 9 折・20%定金鎖定)";
        } else if ("hourly_flex".equals(plan)) {
            discount = 1.0;
            if (!"typhoon_emergency".equals(dispatchCategory)) {
                unitRate = 310;
            }
            planName = "【Tier 2】在地彈性兼職包 (隨叫隨派・時薪 $310・零底薪負擔)";
        } else if ("turnkey".equals(plan)) {
            discount = 0.95;
            planName = "【Tier 3】成果統包專業代耕 (無人機/機耕驗收付款・免監工)";
        }

        int standardTotal = (int) Math.round(totalHours * unitRate);
        int discountedTotal = (int) Math.round(standardTotal * discount);
        int actualTotal = discountedTotal + surcharge;
        int deposit = "early_bird".equals(plan) ? (int) Math.round(discountedTotal * 0.2)
                    : ("turnkey".equals(plan) ? (int) Math.round(discountedTotal * 0.3) : 0);
        int savings = standardTotal - discountedTotal;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cropId", crop != null ? crop.getId() : cropId);
        data.put("cropName", crop != null ? crop.getName() : "未指定作物");
        data.put("area", area);
        data.put("plan", plan);
        data.put("planName", planName);
        data.put("dispatchCategory", dispatchCategory);
        data.put("categoryName", categoryName);
        data.put("suggestedTeam", suggestedTeam);
        data.put("suggestedTeamName", suggestedTeamName);
        data.put("transportZone", zone);
        data.put("transportSurcharge", surcharge);
        data.put("totalHours", totalHours);
        data.put("suggestedWorkers", suggestedWorkers);
        data.put("estDays", estDays);
        data.put("unitRate", unitRate);
        data.put("standardTotal", standardTotal + surcharge);
        data.put("actualTotal", actualTotal);
        data.put("deposit", deposit);
        data.put("savings", savings);

        return data;
    }

    public Order createOrder(Order order) {
        if (order.getId() == null || order.getId().trim().isEmpty()) {
            String year = new SimpleDateFormat("yyyy").format(new Date());
            order.setId("ORD-" + year + "-" + String.format("%03d", orderRepository.count() + 1));
        }
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        }
        if (order.getStatus() == null) {
            order.setStatus("confirmed");
        }
        if (order.getDispatchCategory() == null || order.getDispatchCategory().isBlank()) {
            order.setDispatchCategory("harvest_grading");
        }
        if (order.getForemanName() == null || order.getForemanName().isBlank()) {
            order.setForemanName(order.getFarmerName() != null ? order.getFarmerName() + " (本人帶隊)" : "同農場主本人");
        }
        if (order.getForemanPhone() == null || order.getForemanPhone().isBlank()) {
            order.setForemanPhone(order.getPhone());
        }

        // 自動精算總價
        Map<String, Object> calc = calculateOrder(order.getCropId(), order.getArea(), order.getPlan(), order.getTransportZone(), order.getDispatchCategory());
        order.setCropName((String) calc.get("cropName"));
        order.setPlanName((String) calc.get("planName"));
        order.setCategoryName((String) calc.get("categoryName"));
        order.setTotalHours((int) calc.get("totalHours"));
        order.setSuggestedWorkers((int) calc.get("suggestedWorkers"));
        order.setUnitRate((int) calc.get("unitRate"));
        order.setStandardAmount((int) calc.get("standardTotal"));
        order.setFinalAmount((int) calc.get("actualTotal"));
        order.setDepositAmount((int) calc.get("deposit"));
        order.setTransportSurcharge((int) calc.get("transportSurcharge"));
        if (order.getAssignedTeamId() == null || order.getAssignedTeamId().isBlank()) {
            order.setAssignedTeamId((String) calc.get("suggestedTeam"));
        }

        return orderRepository.save(order);
    }

    public Order updateOrder(String id, Order updated) {
        return orderRepository.findById(id).map(existing -> {
            if (updated.getFarmerName() != null) existing.setFarmerName(updated.getFarmerName());
            if (updated.getPhone() != null) existing.setPhone(updated.getPhone());
            if (updated.getFarmLocation() != null) existing.setFarmLocation(updated.getFarmLocation());
            if (updated.getCropId() != null) existing.setCropId(updated.getCropId());
            if (updated.getCropName() != null) existing.setCropName(updated.getCropName());
            if (updated.getArea() > 0) existing.setArea(updated.getArea());
            if (updated.getPlan() != null) existing.setPlan(updated.getPlan());
            if (updated.getDispatchCategory() != null) {
                existing.setDispatchCategory(updated.getDispatchCategory());
                existing.setCategoryName(updated.getCategoryName());
            }
            if (updated.getForemanName() != null) existing.setForemanName(updated.getForemanName());
            if (updated.getForemanPhone() != null) existing.setForemanPhone(updated.getForemanPhone());
            if (updated.getForemanNotes() != null) existing.setForemanNotes(updated.getForemanNotes());
            if (updated.getStatus() != null) existing.setStatus(updated.getStatus());
            if (updated.getAssignedTeamId() != null) existing.setAssignedTeamId(updated.getAssignedTeamId());
            existing.setDepositPaid(updated.isDepositPaid());
            if (updated.getFinalAmount() > 0) existing.setFinalAmount(updated.getFinalAmount());
            return orderRepository.save(existing);
        }).orElseThrow(() -> new RuntimeException("Order not found: " + id));
    }

    public void deleteOrder(String id) {
        orderRepository.deleteById(id);
    }
}
