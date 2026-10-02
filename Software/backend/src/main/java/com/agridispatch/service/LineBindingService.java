package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.LineUser;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.LineUserRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * LINE 官方帳號使用者：身分查詢、追蹤狀態、帳號綁定與解除
 * 規格：07_系統分析及文件/05_LINE官方帳號拆分規格.md 第參、伍、陸章
 */
@Service
public class LineBindingService {

    public static final Set<String> CHANNELS = Set.of(LineUser.CHANNEL_FARMER, LineUser.CHANNEL_WORKER);
    public static final Set<String> LANGS = Set.of("zh", "vi", "id");

    private final LineUserRepository lineUserRepository;
    private final WorkerRepository workerRepository;
    private final LineIdTokenVerifier idTokenVerifier;
    private final LineBotClient lineBotClient;

    public LineBindingService(LineUserRepository lineUserRepository, WorkerRepository workerRepository,
                              LineIdTokenVerifier idTokenVerifier, LineBotClient lineBotClient) {
        this.lineUserRepository = lineUserRepository;
        this.workerRepository = workerRepository;
        this.idTokenVerifier = idTokenVerifier;
        this.lineBotClient = lineBotClient;
    }

    // =========================================================================
    // 身分查詢（LINE Bot 內部端點用）
    // =========================================================================

    /** 規格 6.3 /api/line/internal/identity：未綁定回 bound:false 與已記錄的語言 */
    public Map<String, Object> identity(String channel, String lineUserId) {
        String ch = requireChannel(channel);
        requireLineUserId(lineUserId);
        return toIdentity(ch, lineUserRepository.findByChannelAndLineUserId(ch, lineUserId.trim()).orElse(null));
    }

    /** 已綁定的那一筆（查詢訂單等需要知道綁定的是誰） */
    public Optional<LineUser> boundUser(String channel, String lineUserId) {
        if (channel == null || lineUserId == null) return Optional.empty();
        return lineUserRepository.findByChannelAndLineUserId(channel, lineUserId.trim()).filter(LineUser::bound);
    }

    /**
     * 規格 6.3 /api/line/internal/follow-state：記錄加好友／封鎖、LINE 顯示名稱、語言（W-06 主動選擇時 langLocked=true）
     */
    public Map<String, Object> followState(String channel, String lineUserId, Boolean followed,
                                           String lang, Boolean langLocked, String displayName) {
        String ch = requireChannel(channel);
        requireLineUserId(lineUserId);
        LineUser u = lineUserRepository.findByChannelAndLineUserId(ch, lineUserId.trim())
                .orElseGet(() -> new LineUser(newId(), ch, lineUserId.trim()));
        if (followed != null) {
            u.setFollowed(followed ? "Y" : "N");
            u.setFollowChangedAt(LocalDateTime.now());
        }
        if (lang != null) {
            if (!LANGS.contains(lang)) throw ApiException.badRequest("lang 必須是 zh、vi、id");
            boolean lock = Boolean.TRUE.equals(langLocked);
            // 未主動選過語言時才接受預設值（LINE 介面語言）；主動選擇一律採用並固定
            if (lock || !u.isLangLocked()) {
                u.setLang(lang);
                if (lock) u.setLangLocked("Y");
            }
        }
        if (displayName != null && !displayName.isBlank()) {
            u.setDisplayName(displayName.trim().length() > 100 ? displayName.trim().substring(0, 100) : displayName.trim());
        }
        return toIdentity(ch, lineUserRepository.save(u));
    }

    // =========================================================================
    // 帳號綁定（規格 5.3、5.4）
    // =========================================================================

    public Map<String, Object> bind(AuthContext auth, String channel, String idToken) {
        String ch = requireChannel(channel);
        if (LineUser.CHANNEL_FARMER.equals(ch) && !auth.isFarmer()) {
            throw ApiException.conflict("此 LINE 帳號（智農派工（農場主））只提供農場主綁定；工作人員請加入「智農派工（工作者）」帳號");
        }
        if (LineUser.CHANNEL_WORKER.equals(ch) && !auth.isWorker()) {
            throw ApiException.conflict("此 LINE 帳號（智農派工（工作者））只提供工作人員綁定；農場主請加入「智農派工（農場主）」帳號");
        }
        LineIdTokenVerifier.Verified verified = idTokenVerifier.verify(idToken);
        String lineUserId = verified.lineUserId();

        LineUser u = lineUserRepository.findByChannelAndLineUserId(ch, lineUserId)
                .orElseGet(() -> new LineUser(newId(), ch, lineUserId));
        if (u.bound() && !samePerson(u, auth)) {
            throw ApiException.conflict("這個 LINE 帳號已綁定其他平台帳號，請先解除原本的綁定");
        }
        for (LineUser other : lineUserRepository.findAllByChannelAndIsBound(ch, "Y")) {
            if (!lineUserId.equals(other.getLineUserId()) && samePerson(other, auth)) {
                throw ApiException.conflict("您的平台帳號已綁定另一個 LINE 帳號，請先解除原本的綁定");
            }
        }

        u.setUsername(auth.sub());
        u.setRefId(auth.ref());
        u.setRole(auth.role());
        u.setIsBound("Y");
        u.setBoundAt(LocalDateTime.now());
        if (verified.displayName() != null) u.setDisplayName(verified.displayName());
        if (!u.isLangLocked()) u.setLang(defaultLang(u));
        Map<String, Object> identity = toIdentity(ch, lineUserRepository.save(u));
        lineBotClient.linkRichMenu(ch, lineUserId, String.valueOf(identity.get("menuKey")));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("channel", ch);
        res.putAll(identity);
        return res;
    }

    /** 本人解除在指定帳號的綁定 */
    public void unbind(AuthContext auth, String channel) {
        String ch = requireChannel(channel);
        LineUser u = lineUserRepository.findAllByChannelAndIsBound(ch, "Y").stream()
                .filter(x -> samePerson(x, auth))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("您在此 LINE 帳號沒有綁定"));
        clearBinding(u);
    }

    /** admin：綁定清單（LINE userId 遮罩） */
    public List<Map<String, Object>> list() {
        return lineUserRepository.findAllByOrderByChannelAscBoundAtDesc().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("channel", u.getChannel());
            m.put("lineUserId", maskLineUserId(u.getLineUserId()));
            m.put("displayName", u.getDisplayName());
            m.put("bound", u.bound());
            m.put("username", u.getUsername());
            m.put("role", u.getRole());
            m.put("refId", u.getRefId());
            m.put("lang", u.getLang());
            m.put("langLocked", u.isLangLocked());
            m.put("followed", u.isFollowed());
            m.put("boundAt", u.getBoundAt());
            return m;
        }).toList();
    }

    /** admin：強制解除綁定（離職、帳號遭冒用） */
    public void adminUnbind(String id) {
        LineUser u = lineUserRepository.findById(id == null ? "" : id)
                .orElseThrow(() -> ApiException.notFound("查無此綁定紀錄"));
        if (!u.bound()) throw ApiException.conflict("此紀錄目前沒有綁定");
        clearBinding(u);
    }

    private void clearBinding(LineUser u) {
        u.setIsBound("N");
        u.setUsername(null);
        u.setRefId(null);
        u.setRole(null);
        u.setBoundAt(null);
        LineUser saved = lineUserRepository.save(u);
        lineBotClient.linkRichMenu(saved.getChannel(), saved.getLineUserId(), menuKey(saved.getChannel(), false, saved.getLang()));
    }

    // =========================================================================
    // 身分組裝
    // =========================================================================

    private Map<String, Object> toIdentity(String channel, LineUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean bound = u != null && u.bound();
        Worker w = bound && isWorkerRole(u.getRole()) && u.getRefId() != null
                ? workerRepository.findById(u.getRefId()).orElse(null) : null;
        String lang = u == null ? "zh" : (u.isLangLocked() ? u.getLang() : (bound ? defaultLang(u) : u.getLang()));
        if (!LANGS.contains(lang)) lang = "zh";

        m.put("bound", bound);
        m.put("role", bound ? u.getRole() : null);
        m.put("subRole", w != null && AuthContext.ROLE_WORKER_LOCAL.equals(u.getRole())
                ? AuthService.workerSubRole(w).toUpperCase() : null);
        m.put("nationality", w != null && AuthContext.ROLE_WORKER_FOREIGN.equals(u.getRole()) ? nationalityCode(w.getNationality()) : null);
        m.put("isLeader", w != null && w.isLeader());
        m.put("lang", lang);
        m.put("langLocked", u != null && u.isLangLocked());
        m.put("followed", u == null || u.isFollowed());
        m.put("menuKey", menuKey(channel, bound, lang));
        return m;
    }

    /** 規格第捌章選單代碼 */
    static String menuKey(String channel, boolean bound, String lang) {
        if (LineUser.CHANNEL_FARMER.equals(channel)) return bound ? "farmer_main" : "farmer_guest";
        return bound ? "worker_main_" + (LANGS.contains(lang) ? lang : "zh") : "worker_guest";
    }

    /** 規格 3.2 第 2 點：國籍預設語言 VN→vi、ID→id，其餘 zh；未綁定沿用已記錄的語言 */
    private String defaultLang(LineUser u) {
        if (!u.bound() || !AuthContext.ROLE_WORKER_FOREIGN.equals(u.getRole()) || u.getRefId() == null) {
            return u.bound() ? "zh" : (LANGS.contains(u.getLang()) ? u.getLang() : "zh");
        }
        String nat = workerRepository.findById(u.getRefId()).map(Worker::getNationality).map(LineBindingService::nationalityCode).orElse(null);
        if ("VN".equals(nat)) return "vi";
        if ("ID".equals(nat)) return "id";
        return "zh";
    }

    /** workers.nationality（中文國名或代碼）→ VN／ID／TH／PH */
    static String nationalityCode(String nationality) {
        if (nationality == null) return null;
        String n = nationality.trim().toUpperCase();
        if (n.contains("越南") || n.equals("VN") || n.startsWith("VIET")) return "VN";
        if (n.contains("印尼") || n.equals("ID") || n.startsWith("INDONESIA")) return "ID";
        if (n.contains("泰國") || n.equals("TH") || n.startsWith("THAI")) return "TH";
        if (n.contains("菲律賓") || n.equals("PH") || n.startsWith("PHILIPPINE")) return "PH";
        return null;
    }

    /** 同一個人：同身分且同 refId（同一位工人可用工號或 users 帳號登入，兩者 refId 相同） */
    private static boolean samePerson(LineUser u, AuthContext auth) {
        if (u.getRefId() != null && auth.ref() != null) {
            return u.getRefId().equalsIgnoreCase(auth.ref()) && auth.role().equals(u.getRole());
        }
        return auth.sub().equals(u.getUsername());
    }

    private static boolean isWorkerRole(String role) {
        return AuthContext.ROLE_WORKER_FOREIGN.equals(role) || AuthContext.ROLE_WORKER_LOCAL.equals(role);
    }

    static String maskLineUserId(String id) {
        if (id == null) return "";
        return id.length() <= 6 ? "******" : id.substring(0, 6) + "****";
    }

    private static String requireChannel(String channel) {
        if (channel == null || !CHANNELS.contains(channel.trim())) throw ApiException.badRequest("channel 必須是 farmer 或 worker");
        return channel.trim();
    }

    private static void requireLineUserId(String lineUserId) {
        if (lineUserId == null || lineUserId.isBlank() || lineUserId.trim().length() > 64) {
            throw ApiException.badRequest("lineUserId 不正確");
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
