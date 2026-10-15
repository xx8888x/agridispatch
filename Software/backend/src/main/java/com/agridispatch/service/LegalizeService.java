package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.LegalizeWorker;
import com.agridispatch.repository.FarmerRepository;
import com.agridispatch.repository.LegalizeWorkerRepository;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工班合法化（黑轉白）：農場主盤點園區現有、尚未合法聘僱或投保的人員，系統依身分分流——
 * 能直接轉白的由合作社聘僱並加保；有條件的先補齊許可；依法不能就地轉白的（失聯移工、借用他人移工）
 * 由平台派合法工班補位，並以「停止使用」解除罰鍰暴露。
 *
 * 法規文字（步驟、說明、備註）與前台 legalize.html 完全相同；標示【待確認】者須由律師或主管機關確認。
 * 失聯移工／借用移工不登錄姓名、證件或聯絡方式（nickname 強制為 null）。
 */
@Service
public class LegalizeService {

    public static final String PATH_DIRECT = "direct";
    public static final String PATH_CONDITIONAL = "conditional";
    public static final String PATH_REPLACE = "replace";

    /** 就業服務法第 63 條：非法聘僱／容留每人新臺幣 15 萬～75 萬元罰鍰（萬元） */
    public static final int FINE_MIN_WAN = 15;
    public static final int FINE_MAX_WAN = 75;

    public static final int MAX_DAYS_PER_WEEK = 7;
    private static final int MAX_NAME = 60;
    private static final int MAX_JOB = 60;

    public record TypeDef(String key, String label, String path, String summary, String note, List<String> steps) {}

    /** 身分類型 → 轉換路徑；順序即前台選單順序 */
    public static final Map<String, TypeDef> TYPES;
    static {
        Map<String, TypeDef> m = new LinkedHashMap<>();
        m.put("local", new TypeDef("local", "本國籍臨時工（未投保）", PATH_DIRECT,
                "由合作社聘僱為農務士，依法加保並申報薪資所得。",
                "已有農保或年滿 65 歲者之投保方式不同【待確認】，由合作社專員個別處理。",
                List.of("填寫農務士加入申請（身分證、存摺影本）",
                        "與合作社簽訂定期勞動契約",
                        "合作社加保勞保／就保／職災保險並提繳勞退",
                        "開通 LINE 綁定與 GPS 上下班打卡",
                        "首次以合作社名義派回本園區")));
        m.put("spouse", new TypeDef("spouse", "新住民（外籍／陸籍配偶）", PATH_DIRECT,
                "依親居留之配偶在台工作免申請許可，可直接由合作社聘僱。",
                "依就業服務法第 48 條、臺灣地區與大陸地區人民關係條例第 17-1 條；陸籍配偶居留類別與工作資格【待確認】。",
                List.of("上傳居留證正反面供合作社審核（確認為依親居留）",
                        "與合作社簽訂勞動契約（提供母語版本）",
                        "合作社加保勞保／就保／職災保險並提繳勞退",
                        "開通 LINE 綁定與 GPS 上下班打卡",
                        "首次以合作社名義派回本園區")));
        m.put("student", new TypeDef("student", "外籍學生／僑生", PATH_CONDITIONAL,
                "須先取得工作許可，學期中每週工時上限 20 小時。",
                "依就業服務法第 50 條；許可效期與農業勞務是否適用【待確認】。未取得許可前不得排班。",
                List.of("確認已向勞動部申請並取得工作許可",
                        "排班設定學期中每週 ≤ 20 小時（寒暑假除外）",
                        "與合作社簽訂勞動契約並加保",
                        "開通 LINE 綁定與打卡（系統自動攔截超時排班）")));
        m.put("borrowed", new TypeDef("borrowed", "借用他人雇主的合法移工", PATH_REPLACE,
                "移工只能為申請許可的原雇主工作，借用即違法，須立即停止，改由外展工班補位。",
                "依就業服務法第 57 條第 1 款。若移工符合轉換雇主條件，須由原雇主及勞動部依法辦理【待確認】，平台不可直接接手。",
                List.of("今日起停止使用，請移工回原雇主處",
                        "結清已提供勞務之工資，勿扣留證件或財物",
                        "同一人力需求改由平台外展工班補位")));
        m.put("runaway", new TypeDef("runaway", "失聯（逃逸）移工", PATH_REPLACE,
                "依法無法就地合法化。平台不登錄個資，只提供到案資訊並派合法工班補位。",
                "自行到案之罰鍰、收容與日後能否再入境，依當時移民署規定與專案而定【待確認】；雇主既往責任請諮詢律師【待確認】。",
                List.of("今日起停止使用；結清已提供勞務之工資，勿扣留證件或財物",
                        "可提供本人到案管道：移民署各縣市專勤隊、勞動部 1955 專線（多語）",
                        "同一人力需求改由平台外展工班補位")));
        TYPES = Collections.unmodifiableMap(m);
    }

    private final LegalizeWorkerRepository repository;
    private final FarmerRepository farmerRepository;
    private final UserRepository userRepository;
    private final AgriSettings settings;
    private final Object idLock = new Object();

    public LegalizeService(LegalizeWorkerRepository repository, FarmerRepository farmerRepository,
                           UserRepository userRepository, AgriSettings settings) {
        this.repository = repository;
        this.farmerRepository = farmerRepository;
        this.userRepository = userRepository;
        this.settings = settings;
    }

    /** 農場主顯示名稱：farmers 表（保留實體，可能沒資料）→ 登入帳號（role=farmer, refId）→ 編號 */
    private String farmerName(String farmerId) {
        return farmerRepository.findById(farmerId).map(f -> f.getName()).filter(n -> n != null && !n.isBlank())
                .or(() -> userRepository.findByRoleAndRefId(AuthContext.ROLE_FARMER, farmerId).stream()
                        .map(u -> u.getName()).filter(n -> n != null && !n.isBlank()).findFirst())
                .orElse(farmerId);
    }

    // ------------------------------------------------------------------ 類型
    public List<Map<String, Object>> types() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TypeDef t : TYPES.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", t.key());
            m.put("label", t.label());
            m.put("path", t.path());
            m.put("summary", t.summary());
            m.put("note", t.note());
            m.put("steps", t.steps());
            out.add(m);
        }
        return out;
    }

    private static TypeDef requireType(String key) {
        TypeDef t = key == null ? null : TYPES.get(key.trim());
        if (t == null) throw ApiException.badRequest("不認得的身分類型：" + key);
        return t;
    }

    public static boolean isReplace(TypeDef t) { return PATH_REPLACE.equals(t.path()); }

    // ------------------------------------------------------------------ 權限
    /**
     * 農場主只能看／改自己的清冊（先判斷角色：demo 模式的 demo token 雖視同 admin，農場主身分登入時仍只看自己）；
     * 真正的 admin 不限；其他角色（工人）不可使用
     */
    private String ownerFor(AuthContext ctx, String requestedFarmerId) {
        if (ctx.isFarmer()) return ctx.ref();
        if (ctx.isAdmin()) {
            return requestedFarmerId == null || requestedFarmerId.isBlank() ? null : requestedFarmerId.trim();
        }
        throw ApiException.forbidden();
    }

    private LegalizeWorker requireOwned(AuthContext ctx, String id) {
        LegalizeWorker w = repository.findById(id).orElseThrow(() -> ApiException.notFound("找不到清冊人員：" + id));
        if (ctx.isFarmer()) {
            if (!Objects.equals(w.getFarmerId(), ctx.ref())) throw ApiException.forbidden();
        } else if (!ctx.isAdmin()) {
            throw ApiException.forbidden();
        }
        return w;
    }

    // ------------------------------------------------------------------ 查詢
    public List<Map<String, Object>> roster(AuthContext ctx, String farmerIdParam) {
        String owner = ownerFor(ctx, farmerIdParam);
        List<LegalizeWorker> rows = owner == null
                ? repository.findAllByOrderByFarmerIdAscCreatedAtAsc()
                : repository.findByFarmerIdOrderByCreatedAtAsc(owner);
        return rows.stream().map(this::toView).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ 新增
    public Map<String, Object> add(AuthContext ctx, Map<String, Object> req) {
        String owner = ownerFor(ctx, str(req.get("farmerId")));
        if (owner == null) throw ApiException.badRequest("admin 新增時須指定 farmerId");
        TypeDef t = requireType(str(req.get("type")));

        LegalizeWorker w = new LegalizeWorker();
        w.setFarmerId(owner);
        w.setWorkerType(t.key());
        applyNickname(w, t, req.get("name"));
        w.setJob(cleanJob(req.get("job")));
        w.setDaysPerWeek(parseDays(req.get("daysPerWeek") != null ? req.get("daysPerWeek") : req.get("days")));
        w.setStepsDone(encode(new boolean[t.steps().size()]));
        LocalDateTime now = LocalDateTime.now();
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        synchronized (idLock) {
            w.setId(IdGenerator.next("LGW-", 6, repository.findIdsByPrefix("LGW-"), repository::existsById));
            return toView(repository.saveAndFlush(w));
        }
    }

    // ------------------------------------------------------------------ 更新（步驟勾選、工作內容、天數）
    public Map<String, Object> update(AuthContext ctx, String id, Map<String, Object> req) {
        LegalizeWorker w = requireOwned(ctx, id);
        TypeDef t = requireType(w.getWorkerType());
        boolean[] done = decode(w.getStepsDone(), t.steps().size());

        if (req.get("done") instanceof List<?> list) {
            if (list.size() != t.steps().size()) {
                throw ApiException.badRequest("done 長度須為 " + t.steps().size());
            }
            for (int i = 0; i < list.size(); i++) done[i] = Boolean.TRUE.equals(list.get(i)) || "true".equals(String.valueOf(list.get(i)));
        }
        if (req.get("stepIndex") != null) {
            int idx;
            try { idx = (int) Math.round(Double.parseDouble(String.valueOf(req.get("stepIndex")))); }
            catch (NumberFormatException e) { throw ApiException.badRequest("stepIndex 必須為數字"); }
            if (idx < 0 || idx >= t.steps().size()) throw ApiException.badRequest("stepIndex 超出範圍（0～" + (t.steps().size() - 1) + "）");
            Object c = req.containsKey("checked") ? req.get("checked") : req.get("value");
            done[idx] = Boolean.TRUE.equals(c) || "true".equals(String.valueOf(c));
        }
        if (req.containsKey("job")) w.setJob(cleanJob(req.get("job")));
        if (req.containsKey("daysPerWeek") || req.containsKey("days")) {
            w.setDaysPerWeek(parseDays(req.get("daysPerWeek") != null ? req.get("daysPerWeek") : req.get("days")));
        }
        if (req.containsKey("name")) applyNickname(w, t, req.get("name"));
        w.setStepsDone(encode(done));
        w.setUpdatedAt(LocalDateTime.now());
        return toView(repository.save(w));
    }

    public void remove(AuthContext ctx, String id) {
        LegalizeWorker w = requireOwned(ctx, id);
        repository.delete(w);
    }

    // ------------------------------------------------------------------ 彙總（科技後台）
    public Map<String, Object> summary(AuthContext ctx) {
        if (!ctx.isStaff() || ctx.isFarmer()) throw ApiException.forbidden();   // 彙總只給後台人員，demo 的農場主 token 也不行
        List<LegalizeWorker> all = repository.findAllByOrderByFarmerIdAscCreatedAtAsc();
        Map<String, List<LegalizeWorker>> byFarmer = new LinkedHashMap<>();
        for (LegalizeWorker w : all) byFarmer.computeIfAbsent(w.getFarmerId(), k -> new ArrayList<>()).add(w);

        List<Map<String, Object>> farms = new ArrayList<>();
        for (Map.Entry<String, List<LegalizeWorker>> e : byFarmer.entrySet()) {
            Map<String, Object> f = aggregate(e.getValue());
            f.put("farmerId", e.getKey());
            f.put("farmerName", farmerName(e.getKey()));
            farms.add(f);
        }
        // 暴露風險高的農場排前面
        farms.sort((a, b) -> Integer.compare((int) b.get("exposed"), (int) a.get("exposed")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("farms", farms);
        out.put("totals", aggregate(all));
        out.put("fineMinWan", FINE_MIN_WAN);
        out.put("fineMaxWan", FINE_MAX_WAN);
        out.put("note", "罰鍰暴露依就業服務法第 63 條（每人 15～75 萬）估算，僅供盤點；過去已發生之責任能否減輕請諮詢律師【待確認】");
        return out;
    }

    private Map<String, Object> aggregate(List<LegalizeWorker> rows) {
        int total = rows.size(), direct = 0, conditional = 0, replace = 0, legalized = 0, inProgress = 0,
                exposed = 0, stopped = 0, replaced = 0;
        for (LegalizeWorker w : rows) {
            TypeDef t = TYPES.get(w.getWorkerType());
            if (t == null) continue;
            boolean[] done = decode(w.getStepsDone(), t.steps().size());
            boolean all = allDone(done);
            switch (t.path()) {
                case PATH_DIRECT -> direct++;
                case PATH_CONDITIONAL -> conditional++;
                default -> replace++;
            }
            if (isReplace(t)) {
                if (all) replaced++;
                else if (done.length > 0 && done[0]) stopped++;
                else exposed++;
            } else if (all) legalized++;
            else inProgress++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("direct", direct);
        m.put("conditional", conditional);
        m.put("replace", replace);
        m.put("legalized", legalized);
        m.put("inProgress", inProgress);
        m.put("exposed", exposed);                 // 仍在使用中的不可轉白人員
        m.put("stopped", stopped);                 // 已停止使用、待補位
        m.put("replaced", replaced);               // 已停止使用並補位
        m.put("gapOpen", exposed + stopped);       // 尚需合法工班補位的人數
        m.put("exposureMinWan", exposed * FINE_MIN_WAN);
        m.put("exposureMaxWan", exposed * FINE_MAX_WAN);
        return m;
    }

    // ------------------------------------------------------------------ demo 重設
    /** demo 模式才有：清掉呼叫者（或 admin 指定農場主）的清冊並回填示範資料（deleteBy 需要交易） */
    @Transactional
    public int resetDemo(AuthContext ctx, String farmerIdParam) {
        if (!settings.isDemoEnabled()) throw ApiException.notFound("NOT_FOUND");
        String owner = ownerFor(ctx, farmerIdParam);
        if (owner == null) throw ApiException.badRequest("admin 重設時須指定 farmerId");
        repository.deleteByFarmerId(owner);
        repository.flush();
        List<LegalizeWorker> seed = demoRoster(owner);
        repository.saveAll(seed);
        return seed.size();
    }

    /** 與前台 legalize.html 的 DEMO_ROSTER 相同的 7 筆示範資料（F001 用），其他農場主給精簡版 */
    public static List<LegalizeWorker> demoRoster(String farmerId) {
        LocalDateTime base = LocalDateTime.of(2026, 9, 20, 9, 0);
        List<Object[]> rows = "F001".equals(farmerId) ? List.of(
                new Object[]{"local", "阿美姐", "採收、分級", 6, "1,1,1,0,0"},
                new Object[]{"local", "林伯", "剪枝", 4, "1,0,0,0,0"},
                new Object[]{"spouse", "阮氏蘭", "套袋、採收", 5, "1,1,1,1,1"},
                new Object[]{"student", "小黃（僑生）", "包裝", 2, "0,0,0,0"},
                new Object[]{"borrowed", null, "搬運", 3, "0,0,0"},
                new Object[]{"runaway", null, "採收", 6, "0,0,0"},
                new Object[]{"runaway", null, "採收", 6, "0,0,0"}
        ) : List.of(
                new Object[]{"local", "阿財", "採收", 5, "1,1,0,0,0"},
                new Object[]{"borrowed", null, "搬運", 3, "0,0,0"}
        );
        List<LegalizeWorker> out = new ArrayList<>();
        int seq = "F001".equals(farmerId) ? 1 : 8;
        for (Object[] r : rows) {
            LegalizeWorker w = new LegalizeWorker();
            w.setId(String.format("LGW-%06d", seq++));
            w.setFarmerId(farmerId);
            w.setWorkerType((String) r[0]);
            w.setNickname((String) r[1]);
            w.setJob((String) r[2]);
            w.setDaysPerWeek((Integer) r[3]);
            w.setStepsDone((String) r[4]);
            w.setCreatedAt(base.plusMinutes(out.size()));
            w.setUpdatedAt(base.plusMinutes(out.size()));
            out.add(w);
        }
        return out;
    }

    // ------------------------------------------------------------------ 檢視
    public Map<String, Object> toView(LegalizeWorker w) {
        TypeDef t = TYPES.get(w.getWorkerType());
        int stepCount = t != null ? t.steps().size() : 0;
        boolean[] done = decode(w.getStepsDone(), stepCount);
        boolean all = allDone(done);
        boolean stoppedOnly = t != null && isReplace(t) && done.length > 0 && done[0] && !all;
        String status;
        if (t != null && isReplace(t)) status = all ? "replaced" : (stoppedOnly ? "stopped" : "in_use");
        else status = all ? "legalized" : "in_progress";

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("farmerId", w.getFarmerId());
        m.put("type", w.getWorkerType());
        m.put("typeLabel", t != null ? t.label() : w.getWorkerType());
        m.put("path", t != null ? t.path() : null);
        m.put("name", w.getNickname());
        m.put("job", w.getJob());
        m.put("daysPerWeek", w.getDaysPerWeek());
        List<Boolean> doneList = new ArrayList<>();
        for (boolean b : done) doneList.add(b);
        m.put("done", doneList);
        m.put("doneCount", (int) doneList.stream().filter(Boolean::booleanValue).count());
        m.put("stepCount", stepCount);
        m.put("isDone", all);
        m.put("isStopped", t != null && isReplace(t) && done.length > 0 && done[0]);
        m.put("status", status);
        m.put("createdAt", w.getCreatedAt());
        m.put("updatedAt", w.getUpdatedAt());
        return m;
    }

    // ------------------------------------------------------------------ 工具
    private static void applyNickname(LegalizeWorker w, TypeDef t, Object raw) {
        if (isReplace(t)) { w.setNickname(null); return; }   // 不登錄個資
        String name = raw == null ? "" : String.valueOf(raw).trim();
        if (name.isEmpty()) throw ApiException.badRequest("請填寫稱呼");
        if (name.length() > MAX_NAME) throw ApiException.badRequest("稱呼最多 " + MAX_NAME + " 字");
        w.setNickname(name);
    }

    private static String cleanJob(Object raw) {
        String job = raw == null ? "" : String.valueOf(raw).trim();
        if (job.isEmpty()) return "農務";
        if (job.length() > MAX_JOB) throw ApiException.badRequest("工作內容最多 " + MAX_JOB + " 字");
        return job;
    }

    private static int parseDays(Object raw) {
        if (raw == null) return 5;
        int d;
        try { d = (int) Math.round(Double.parseDouble(String.valueOf(raw))); }
        catch (NumberFormatException e) { throw ApiException.badRequest("每週天數必須為數字"); }
        if (d < 1 || d > MAX_DAYS_PER_WEEK) throw ApiException.badRequest("每週天數須介於 1～" + MAX_DAYS_PER_WEEK);
        return d;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    static String encode(boolean[] done) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < done.length; i++) { if (i > 0) sb.append(','); sb.append(done[i] ? '1' : '0'); }
        return sb.toString();
    }

    static boolean[] decode(String s, int size) {
        boolean[] out = new boolean[size];
        if (s == null || s.isBlank()) return out;
        String[] parts = s.split(",");
        for (int i = 0; i < Math.min(size, parts.length); i++) out[i] = "1".equals(parts[i].trim()) || "true".equals(parts[i].trim());
        return out;
    }

    private static boolean allDone(boolean[] done) {
        if (done.length == 0) return false;
        for (boolean b : done) if (!b) return false;
        return true;
    }
}
