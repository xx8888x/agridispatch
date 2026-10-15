package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.entity.Referral;
import com.agridispatch.entity.ReferralCode;
import com.agridispatch.entity.User;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.ReferralCodeRepository;
import com.agridispatch.repository.ReferralRepository;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.AuthContext;
import com.agridispatch.security.RegisterRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 推薦裂變：農場主與工人用邀請連結（Facebook、LINE、複製）邀請其他農場主或工人。
 *
 * - 每人一組推薦碼（getMine 時才產生），連結帶 ?ref=code&amp;t=farmer|worker&amp;c=fb|line|copy|share
 * - 農場主被邀請 → 在農場主註冊頁自助註冊，registerFarmer 帶入 refCode，建立一筆 registered
 * - 工人被邀請 → 工人須經合作社查驗證件才能建檔（不能自助註冊），改為「意願登記」，合作社在後台跟進
 * - 不含獎勵金額：推廣獎勵的稅務與法規認定【待確認】，目前只記錄推薦關係與成效
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    private static final Set<String> REFERRER_ROLES = Set.of(
            AuthContext.ROLE_FARMER, AuthContext.ROLE_WORKER_FOREIGN, AuthContext.ROLE_WORKER_LOCAL);
    private static final Set<String> CHANNELS = Set.of("fb", "line", "copy", "share", "other");
    private static final Set<String> STAFF_STATUSES = Set.of(Referral.CONTACTED, Referral.ONBOARDED, Referral.CLOSED, Referral.PENDING);

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 去掉 I O 0 1，避免念錯
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReferralCodeRepository codeRepository;
    private final ReferralRepository referralRepository;
    private final UserRepository userRepository;
    private final WorkerRepository workerRepository;
    /** 免登入的意願登記：同一 IP 每小時 10 次（與註冊各自計數） */
    private final RegisterRateLimiter leadLimiter = new RegisterRateLimiter(10, 60);

    public ReferralService(ReferralCodeRepository codeRepository, ReferralRepository referralRepository,
                           UserRepository userRepository, WorkerRepository workerRepository) {
        this.codeRepository = codeRepository;
        this.referralRepository = referralRepository;
        this.userRepository = userRepository;
        this.workerRepository = workerRepository;
    }

    // =========================================================================
    // 推薦人：我的推薦碼與成效
    // =========================================================================

    /** 我的推薦碼、漏斗統計與被邀請者清單（姓名遮罩、不含電話與合作社備註） */
    @Transactional
    public Map<String, Object> mine(AuthContext ctx) {
        requireReferrer(ctx);
        ReferralCode code = findOrCreateCode(ctx);
        List<Referral> list = referralRepository.findByReferrerUsernameOrderByCreatedAtDesc(ctx.sub());

        int farmers = 0, leads = 0, onboarded = 0;
        List<Map<String, Object>> invitees = new ArrayList<>();
        for (Referral r : list) {
            if ("farmer".equals(r.getInviteeRole())) farmers++; else leads++;
            if (Referral.ONBOARDED.equals(r.getStatus())) onboarded++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", r.getInviteeRole());
            m.put("name", maskName(r.getInviteeName()));
            m.put("status", r.getStatus());
            m.put("channel", r.getChannel());
            m.put("createdAt", r.getCreatedAt());
            invitees.add(m);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("opens", code.getClickCount());
        stats.put("farmersRegistered", farmers);
        stats.put("workerLeads", leads);
        stats.put("workersOnboarded", onboarded);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", code.getCode());
        res.put("role", ctx.role());
        res.put("stats", stats);
        res.put("invitees", invitees);
        return res;
    }

    private synchronized ReferralCode findOrCreateCode(AuthContext ctx) {
        return codeRepository.findByOwnerUsername(ctx.sub()).orElseGet(() -> {
            String code;
            do {
                StringBuilder sb = new StringBuilder(CODE_LENGTH);
                for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
                code = sb.toString();
            } while (codeRepository.existsById(code));
            return codeRepository.save(new ReferralCode(code, ctx.sub(), ctx.ref(), ctx.role()));
        });
    }

    private static void requireReferrer(AuthContext ctx) {
        if (!REFERRER_ROLES.contains(ctx.role())) throw ApiException.forbidden();
    }

    // =========================================================================
    // 被邀請者（免登入）
    // =========================================================================

    /** 開啟邀請連結：計一次開啟，回邀請人（姓名遮罩）。碼無效回 valid=false，不丟錯誤 */
    @Transactional
    public Map<String, Object> open(String rawCode) {
        Map<String, Object> res = new LinkedHashMap<>();
        Optional<ReferralCode> code = findCode(rawCode);
        if (code.isEmpty()) {
            res.put("valid", false);
            return res;
        }
        codeRepository.incrementClicks(code.get().getCode());
        res.put("valid", true);
        res.put("inviterName", maskName(displayName(code.get())));
        res.put("inviterRole", code.get().getOwnerRole());
        return res;
    }

    /**
     * 工人意願登記（工人不能自助註冊，須由合作社查驗證件後建檔）：
     * 同一邀請碼、同一電話重複送出視為同一筆（不報錯、不洩漏是否已存在）。
     */
    @Transactional
    public void submitWorkerLead(Map<String, Object> req, String clientIp) {
        leadLimiter.acquire(clientIp);
        ReferralCode code = findCode(str(req.get("code")))
                .orElseThrow(() -> ApiException.badRequest("邀請連結無效或已失效"));
        String name = trim(str(req.get("name")));
        if (name == null || name.length() > 50) throw ApiException.badRequest("請填寫姓名（50 字以內）");
        String phone = str(req.get("phone")) == null ? "" : str(req.get("phone")).replaceAll("[\\s()-]", "");
        if (!phone.matches("\\+?\\d{8,15}")) throw ApiException.badRequest("請填寫可以聯絡到您的電話");
        if (!Boolean.TRUE.equals(req.get("pdpaConsent"))) throw ApiException.badRequest("請先閱讀並同意個人資料蒐集告知事項");
        if (isOwnPhone(code, phone)) throw ApiException.badRequest("不能用自己的電話登記");

        if (referralRepository.existsByCodeAndInviteePhone(code.getCode(), phone)) return;
        Referral r = newReferral(code, "worker", name, channel(req.get("channel")));
        r.setInviteePhone(phone);
        r.setStatus(Referral.PENDING);
        referralRepository.save(r);
    }

    /**
     * 農場主自助註冊成功後記一筆推薦（AuthService.registerFarmer 呼叫）。
     * 推薦碼無效、自己邀請自己都直接略過；任何錯誤都不可影響註冊本身。
     */
    public void recordFarmerRegistration(String rawCode, String rawChannel, String farmerId, String name, String phone) {
        if (rawCode == null || rawCode.isBlank()) return;
        try {
            Optional<ReferralCode> code = findCode(rawCode);
            if (code.isEmpty() || phone.equals(code.get().getOwnerUsername())) return;
            Referral r = newReferral(code.get(), "farmer", name, channel(rawChannel));
            r.setInviteePhone(phone);
            r.setInviteeRefId(farmerId);
            r.setStatus(Referral.REGISTERED);
            referralRepository.save(r);
        } catch (RuntimeException e) {
            log.warn("記錄推薦失敗（不影響註冊）：{}", e.toString());
        }
    }

    // =========================================================================
    // 合作社／科技後台
    // =========================================================================

    /** 全部推薦紀錄（新的在前），含被邀請者電話與推薦人 */
    public List<Map<String, Object>> list() {
        return referralRepository.findAllByOrderByCreatedAtDesc().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("inviteeRole", r.getInviteeRole());
            m.put("inviteeName", r.getInviteeName());
            m.put("inviteePhone", r.getInviteePhone());
            m.put("inviteeRefId", r.getInviteeRefId());
            m.put("referrerUsername", r.getReferrerUsername());
            m.put("referrerRole", r.getReferrerRole());
            m.put("referrerName", nameOf(r.getReferrerUsername(), r.getReferrerRole()));
            m.put("channel", r.getChannel());
            m.put("status", r.getStatus());
            m.put("note", r.getNote());
            m.put("createdAt", r.getCreatedAt());
            m.put("updatedAt", r.getUpdatedAt());
            return m;
        }).toList();
    }

    /** 合作社更新工人意願登記的跟進狀態與備註 */
    @Transactional
    public Map<String, Object> updateStatus(String id, String status, String note) {
        Referral r = referralRepository.findById(id).orElseThrow(() -> ApiException.notFound("查無推薦紀錄：" + id));
        if (!"worker".equals(r.getInviteeRole())) throw ApiException.badRequest("農場主註冊的推薦紀錄不需跟進");
        if (status == null || !STAFF_STATUSES.contains(status)) {
            throw ApiException.badRequest("status 必須為 pending / contacted / onboarded / closed");
        }
        String n = trim(note);
        if (n != null && n.length() > 500) throw ApiException.badRequest("備註請在 500 字以內");
        r.setStatus(status);
        if (n != null) r.setNote(n);
        r.setUpdatedAt(LocalDateTime.now());
        referralRepository.save(r);
        return Map.of("id", r.getId(), "status", r.getStatus());
    }

    // =========================================================================
    // 內部工具
    // =========================================================================

    private Referral newReferral(ReferralCode code, String inviteeRole, String inviteeName, String channel) {
        Referral r = new Referral();
        String prefix = "RF-" + LocalDate.now().getYear() + "-";
        r.setId(IdGenerator.next(prefix, 3, referralRepository.findIdsByPrefix(prefix), referralRepository::existsById));
        r.setCode(code.getCode());
        r.setReferrerUsername(code.getOwnerUsername());
        r.setReferrerRole(code.getOwnerRole());
        r.setInviteeRole(inviteeRole);
        r.setInviteeName(inviteeName.length() > 50 ? inviteeName.substring(0, 50) : inviteeName);
        r.setChannel(channel);
        return r;
    }

    private Optional<ReferralCode> findCode(String raw) {
        String c = trim(raw);
        if (c == null || c.length() > 12) return Optional.empty();
        return codeRepository.findById(c.toUpperCase(Locale.ROOT));
    }

    private boolean isOwnPhone(ReferralCode code, String phone) {
        if (phone.equals(code.getOwnerUsername())) return true;   // 農場主帳號即手機號碼
        return workerRepository.findById(code.getOwnerUsername())
                .map(w -> phone.equals(w.getPhone() == null ? null : w.getPhone().replaceAll("[\\s()-]", "")))
                .orElse(false);
    }

    private String displayName(ReferralCode code) {
        return nameOf(code.getOwnerUsername(), code.getOwnerRole());
    }

    private String nameOf(String username, String role) {
        Optional<User> u = userRepository.findById(username);
        if (u.isPresent() && u.get().getName() != null) return u.get().getName();
        Optional<Worker> w = workerRepository.findById(username);
        if (w.isPresent()) {
            return w.get().getChineseName() != null && !w.get().getChineseName().isBlank() ? w.get().getChineseName() : w.get().getName();
        }
        return username;
    }

    /** 王大明 → 王○明；兩個字 → 王○；其餘外文姓名只留第一個字 */
    static String maskName(String name) {
        if (name == null || name.isBlank()) return "";
        String n = name.trim();
        int len = n.codePointCount(0, n.length());
        if (len <= 1) return n;
        String first = new String(Character.toChars(n.codePointAt(0)));
        if (len == 2) return first + "○";
        if (n.chars().anyMatch(c -> c < 0x2E80)) return first + "○○";   // 含拉丁字母（外籍姓名）
        return first + "○" + new String(Character.toChars(n.codePointBefore(n.length())));
    }

    private static String channel(Object raw) {
        String c = raw == null ? null : raw.toString().trim().toLowerCase(Locale.ROOT);
        return c != null && CHANNELS.contains(c) ? c : "other";
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
