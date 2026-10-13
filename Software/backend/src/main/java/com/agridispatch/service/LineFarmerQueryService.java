package com.agridispatch.service;

import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * LINE 農場主帳號的本人查詢（POST /api/line/internal/query，channel=farmer；規格 05 第 4.1 節）
 *   orders  F-01 我的訂單：指定單號或作物的那筆，否則挑最相關的一筆（施工中 > 已排定 > 待確認 > 其他），附其餘訂單摘要
 *   weather F-04 天候避險：目前天候＋本人施工中、或 3 天內開工的已排定訂單 × 該產區天氣與工班是否改室內
 *
 * 回傳的是資料，不是 LINE 訊息：由 LINE Bot 組卡片（與工作者帳號 LineWorkerQueryService 相同分工）。
 * 不回工人個人資料（規格第拾章第 3 點）：只回工班名稱與人數、農場主自己填的帶隊對口；不回後台天候訊息（內含營收）。
 */
@Service
public class LineFarmerQueryService {
    /** 天候避險涵蓋的天數（今天起算） */
    static final int WEATHER_DAYS = 3;
    /** 訂單卡附帶的其他訂單筆數上限 */
    static final int OTHER_ORDERS = 3;

    private final OrderRepository orderRepository;
    private final DispatchTeamRepository teamRepository;
    private final DispatchService dispatchService;

    public LineFarmerQueryService(OrderRepository orderRepository, DispatchTeamRepository teamRepository,
                                  DispatchService dispatchService) {
        this.orderRepository = orderRepository;
        this.teamRepository = teamRepository;
        this.dispatchService = dispatchService;
    }

    /** farmerId 為綁定時的 ref（F001）；不支援的動作回傳空 Optional */
    public Optional<Map<String, Object>> query(String farmerId, String action, String text) {
        if (farmerId == null || action == null) return Optional.empty();
        List<Order> mine = orderRepository.findAll().stream()
                .filter(o -> farmerId.equalsIgnoreCase(o.getFarmerId())).toList();
        return switch (action) {
            case "orders" -> Optional.of(orders(mine, text == null ? "" : text.trim().toLowerCase()));
            case "weather" -> Optional.of(weather(mine));
            default -> Optional.empty();
        };
    }

    // ------------------------------------------------------------------ F-01

    private Map<String, Object> orders(List<Order> mine, String text) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", "orders");
        d.put("total", mine.size());
        if (mine.isEmpty()) return d;
        List<Order> ranked = new ArrayList<>(mine);
        ranked.sort(Comparator.comparingInt(LineFarmerQueryService::rank)
                .thenComparing(o -> o.getStartDate() == null ? LocalDate.MAX : o.getStartDate())
                .thenComparing(o -> Objects.toString(o.getCreatedAt(), ""), Comparator.reverseOrder()));
        Order target = ranked.get(0);
        for (Order o : mine) {
            if (!text.isEmpty() && (text.contains(o.getId().toLowerCase())
                    || (o.getCropName() != null && !o.getCropName().isBlank() && text.contains(o.getCropName().toLowerCase())))) {
                target = o;
                break;
            }
        }
        d.put("order", detail(target));
        List<Map<String, Object>> others = new ArrayList<>();
        for (Order o : ranked) {
            if (o == target) continue;
            if (others.size() >= OTHER_ORDERS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("statusLabel", OrderService.statusLabel(o.getStatus()));
            m.put("startDate", o.getStartDate() == null ? null : o.getStartDate().toString());
            m.put("cropName", o.getCropName());
            others.add(m);
        }
        d.put("others", others);
        return d;
    }

    /** 預設挑選順序：施工中、已排定、待確認、已完成、已取消 */
    private static int rank(Order o) {
        return switch (Objects.toString(o.getStatus(), "")) {
            case OrderService.IN_PROGRESS -> 0;
            case OrderService.CONFIRMED -> 1;
            case OrderService.PENDING -> 2;
            case OrderService.COMPLETED -> 3;
            default -> 4;
        };
    }

    /** 單筆訂單的卡片資料（F-01；LINE 體驗頁的示範推播也用這份資料） */
    public Map<String, Object> detail(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("status", o.getStatus());
        m.put("statusLabel", OrderService.statusLabel(o.getStatus()));
        m.put("cropName", o.getCropName());
        m.put("categoryName", o.getCategoryName());
        m.put("area", o.getArea());
        m.put("farmName", o.getFarmName());
        m.put("farmLocation", o.getFarmLocation());
        m.put("startDate", o.getStartDate() == null ? null : o.getStartDate().toString());
        m.put("endDate", o.getEndDate() == null ? null : o.getEndDate().toString());
        m.put("timeSlotLabel", Order.slotLabel(o.slot()));
        m.put("teamName", teamOf(o).map(DispatchTeam::getName).orElse(null));
        int crew = o.crewList().size();
        m.put("workersPerDay", crew > 0 ? crew : o.getRequestedWorkers() != null ? o.getRequestedWorkers() : o.getSuggestedWorkers());
        m.put("foremanName", o.getForemanName());
        m.put("foremanPhone", o.getForemanPhone());
        m.put("foremanNotes", o.getForemanNotes());
        m.put("preferredWorkerName", o.getPreferredWorkerName());
        m.put("finalAmount", o.getFinalAmount());
        m.put("depositAmount", o.getDepositAmount());
        m.put("depositPaid", o.isDepositPaid());
        return m;
    }

    // ------------------------------------------------------------------ F-04

    private Map<String, Object> weather(List<Order> mine) {
        LocalDate today = LocalDate.now();
        LocalDate until = today.plusDays(WEATHER_DAYS);
        Map<String, Object> state = dispatchService.getWeatherStateSnapshot();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regions = (List<Map<String, Object>>) state.getOrDefault("regionalWeather", List.of());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", "weather");
        d.put("date", today.toString());
        d.put("weather", state.get("currentWeather"));
        d.put("alertLevel", state.get("alertLevel"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Order o : mine) {
            String st = o.getStatus();
            boolean active = OrderService.IN_PROGRESS.equals(st) || OrderService.CONFIRMED.equals(st);
            if (!active) continue;
            boolean inWindow = o.hasPeriod()
                    ? !o.getEndDate().isBefore(today) && !o.getStartDate().isAfter(until)
                    : OrderService.IN_PROGRESS.equals(st);   // 沒填工期但施工中的也算（與天候推播相同）
            if (!inWindow) continue;
            boolean workingToday = o.hasPeriod() ? o.coversDate(today) : true;
            Optional<DispatchTeam> team = teamOf(o);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("statusLabel", OrderService.statusLabel(st));
            m.put("cropName", o.getCropName());
            m.put("place", o.getFarmName() != null && !o.getFarmName().isBlank() ? o.getFarmName() : o.getFarmLocation());
            m.put("startDate", o.getStartDate() == null ? null : o.getStartDate().toString());
            m.put("endDate", o.getEndDate() == null ? null : o.getEndDate().toString());
            m.put("timeSlotLabel", Order.slotLabel(o.slot()));
            m.put("workingToday", workingToday);
            // 與天候推播相同：工班狀態為 hedged_indoor 表示今天改到室內避險作業
            m.put("teamIndoor", workingToday && team.map(t -> "hedged_indoor".equals(t.getStatus())).orElse(false));
            m.put("region", regionFor(o.getFarmLocation(), regions));
            items.add(m);
        }
        items.sort(Comparator.comparing(m -> Objects.toString(m.get("startDate"), "")));
        d.put("orders", items);
        return d;
    }

    /** 依農場地址找對應產區天氣（「屏東枋山」對「屏東縣枋山鄉」；產區卡有 county／district 時優先比對）；找不到回 null */
    static Map<String, Object> regionFor(String location, List<Map<String, Object>> regions) {
        String loc = norm(location);
        if (loc.isEmpty() || regions == null) return null;
        for (Map<String, Object> r : regions) {
            String county = stripUnit(norm(Objects.toString(r.get("county"), "")));
            String district = stripUnit(norm(Objects.toString(r.get("district"), "")));
            String region = norm(Objects.toString(r.get("region"), ""));
            boolean hit = !county.isEmpty()
                    ? loc.contains(county) && (district.isEmpty() || loc.contains(district))
                    : region.length() >= 4 && loc.contains(region.substring(0, 2)) && loc.contains(region.substring(2));
            if (hit) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String k : List.of("region", "condition", "temp", "rainChance", "advice")) m.put(k, r.get(k));
                return m;
            }
        }
        return null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace('臺', '台').replaceAll("\\s+", "");
    }

    private static String stripUnit(String s) {
        return s.length() > 2 ? s.replaceAll("[縣市鄉鎮區]$", "") : s;
    }

    private Optional<DispatchTeam> teamOf(Order o) {
        return o.getAssignedTeamId() == null ? Optional.empty() : teamRepository.findById(o.getAssignedTeamId());
    }
}
