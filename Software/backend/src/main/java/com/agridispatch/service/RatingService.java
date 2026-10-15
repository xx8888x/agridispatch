package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.TeamRating;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.TeamRatingRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 工班評價：農場主在工班出工後為該訂單評分（1～5 星＋標籤＋留言），每張訂單一筆、可修改；
 * 依工班彙總平均分數，供派工媒合與工班履歷參考。
 */
@Service
public class RatingService {

    /** 可評價的訂單狀態：已出工（進行中）或已完工 */
    public static final Set<String> RATEABLE_STATUS = Set.of("in_progress", "completed");

    /** 允許的評價標籤（固定選項，避免自由文字標籤） */
    public static final List<String> ALLOWED_TAGS = List.of(
            "準時到場", "做工細心", "效率高", "溝通順暢", "遵守安全規範", "需加強訓練");

    private static final int MAX_COMMENT = 300;

    private final TeamRatingRepository ratingRepository;
    private final DispatchTeamRepository teamRepository;
    private final OrderService orderService;

    public RatingService(TeamRatingRepository ratingRepository, DispatchTeamRepository teamRepository,
                         OrderService orderService) {
        this.ratingRepository = ratingRepository;
        this.teamRepository = teamRepository;
        this.orderService = orderService;
    }

    /** 新增或修改訂單評價：限訂單擁有者或 admin，且工班已出工 */
    public TeamRating rate(AuthContext ctx, String orderId, Object scoreRaw, Object tagsRaw, Object commentRaw) {
        Order order = orderService.requireOrder(orderId);
        orderService.checkOwnerOrAdmin(ctx, order);
        if (!RATEABLE_STATUS.contains(order.getStatus())) {
            throw ApiException.conflict("工班出工後才能評價（目前狀態：" + order.getStatus() + "）");
        }
        String teamId = order.getAssignedTeamId();
        if (teamId == null || !teamRepository.existsById(teamId)) {
            throw ApiException.conflict("此訂單尚未分派工班，無法評價");
        }

        int score = parseScore(scoreRaw);
        List<String> tags = parseTags(tagsRaw);
        String comment = cleanComment(commentRaw);

        LocalDateTime now = LocalDateTime.now();
        TeamRating r = ratingRepository.findByOrderId(orderId).orElseGet(() -> {
            TeamRating n = new TeamRating();
            n.setId("RAT-" + orderId);
            n.setOrderId(orderId);
            n.setCreatedAt(now);
            return n;
        });
        r.setTeamId(teamId);
        r.setFarmerId(ctx.ref());
        r.setScore(score);
        r.setTags(String.join(",", tags));
        r.setComment(comment);
        r.setUpdatedAt(now);
        return ratingRepository.save(r);
    }

    /** 查詢某訂單的評價（讀取權限同訂單） */
    public Optional<TeamRating> getForOrder(AuthContext ctx, String orderId) {
        Order order = orderService.requireOrder(orderId);
        orderService.checkReadable(ctx, order);
        return ratingRepository.findByOrderId(orderId);
    }

    /** 全部工班的評價彙總 */
    public List<Map<String, Object>> teamSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DispatchTeam t : teamRepository.findAll()) {
            out.add(summarize(t, ratingRepository.findByTeamIdOrderByCreatedAtDesc(t.getId())));
        }
        return out;
    }

    /** 單一工班彙總＋最近 10 筆評價 */
    public Map<String, Object> teamDetail(AuthContext ctx, String teamId) {
        DispatchTeam team = teamRepository.findById(teamId)
                .orElseThrow(() -> ApiException.notFound("找不到工班：" + teamId));
        List<TeamRating> list = ratingRepository.findByTeamIdOrderByCreatedAtDesc(teamId);
        Map<String, Object> m = summarize(team, list);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (TeamRating r : list.subList(0, Math.min(10, list.size()))) {
            recent.add(toView(r, ctx != null && ctx.isStaff()));
        }
        m.put("recent", recent);
        return m;
    }

    /** 回傳給前端的評價內容（不含評分者編號；訂單編號只給 admin） */
    public Map<String, Object> toView(TeamRating r, boolean includeOrderId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        if (includeOrderId) m.put("orderId", r.getOrderId());
        m.put("teamId", r.getTeamId());
        m.put("score", r.getScore());
        m.put("tags", splitTags(r.getTags()));
        m.put("comment", r.getComment() == null ? "" : r.getComment());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        m.put("updatedAt", r.getUpdatedAt() == null ? null : r.getUpdatedAt().toString());
        return m;
    }

    private Map<String, Object> summarize(DispatchTeam team, List<TeamRating> list) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teamId", team.getId());
        m.put("teamName", team.getName());
        m.put("count", list.size());
        double avg = list.stream().mapToInt(TeamRating::getScore).average().orElse(0);
        m.put("average", list.isEmpty() ? null : Math.round(avg * 10) / 10.0);
        Map<String, Integer> tagCounts = new LinkedHashMap<>();
        for (String tag : ALLOWED_TAGS) tagCounts.put(tag, 0);
        for (TeamRating r : list) {
            for (String tag : splitTags(r.getTags())) tagCounts.merge(tag, 1, Integer::sum);
        }
        tagCounts.values().removeIf(c -> c == 0);
        m.put("tagCounts", tagCounts);
        return m;
    }

    private static int parseScore(Object raw) {
        int score;
        try {
            score = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception e) {
            throw ApiException.badRequest("score 必須為 1～5 的整數");
        }
        if (raw instanceof Number n && n.doubleValue() != score) {
            throw ApiException.badRequest("score 必須為 1～5 的整數");
        }
        if (score < 1 || score > 5) throw ApiException.badRequest("score 必須為 1～5 的整數");
        return score;
    }

    private static List<String> parseTags(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof Collection<?> c)) throw ApiException.badRequest("tags 必須為陣列");
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (Object o : c) {
            String tag = String.valueOf(o).trim();
            if (!ALLOWED_TAGS.contains(tag)) throw ApiException.badRequest("不支援的標籤：" + tag);
            tags.add(tag);
        }
        return new ArrayList<>(tags);
    }

    private static String cleanComment(Object raw) {
        if (raw == null) return "";
        String s = String.valueOf(raw).replaceAll("[\\p{Cntrl}&&[^\n]]", "").trim();
        if (s.length() > MAX_COMMENT) throw ApiException.badRequest("留言最多 " + MAX_COMMENT + " 字");
        return s;
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
