package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.common.LaborConstants;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.Farmer;
import com.agridispatch.entity.User;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.FarmerRepository;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.AuthContext;
import com.agridispatch.security.LoginRateLimiter;
import com.agridispatch.security.PasswordHasher;
import com.agridispatch.security.RegisterRateLimiter;
import com.agridispatch.security.TokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 認證服務：帳密登入、demo 登入、Token 身分還原、密碼變更
 * （已移除舊版寫死的 farmer / worker_labor 後門分支與全體農工共用密碼）
 */
@Service
public class AuthService {

    /** 內部登入主體：來自 users 表或 workers 表 */
    private record Principal(String username, String role, String refId, User user, Worker worker) {}

    private static final Map<String, String> DEMO_DEFAULT_REF = Map.of(
            AuthContext.ROLE_ADMIN, "ADM01",
            AuthContext.ROLE_FARMER, "F001",
            AuthContext.ROLE_WORKER_FOREIGN, "W001",
            AuthContext.ROLE_WORKER_LOCAL, "W026"
    );

    private final UserRepository userRepository;
    private final WorkerRepository workerRepository;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final AgriSettings settings;
    private final LoginRateLimiter loginRateLimiter;
    private final FarmerRepository farmerRepository;
    private final RegisterRateLimiter registerRateLimiter;

    public AuthService(UserRepository userRepository, WorkerRepository workerRepository,
                       PasswordHasher passwordHasher, TokenService tokenService, AgriSettings settings,
                       LoginRateLimiter loginRateLimiter, FarmerRepository farmerRepository,
                       RegisterRateLimiter registerRateLimiter) {
        this.userRepository = userRepository;
        this.workerRepository = workerRepository;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.settings = settings;
        this.loginRateLimiter = loginRateLimiter;
        this.farmerRepository = farmerRepository;
        this.registerRateLimiter = registerRateLimiter;
    }

    // =========================================================================
    // 登入
    // =========================================================================

    /**
     * 帳密登入：identifier 可為 users 帳號、農工工號、手機、居留證號 (ARC)、護照號碼
     * 失敗次數限制（規格 03 第 5 節）：帳號或來源 IP 鎖定中直接回 429，**不執行 PBKDF2**；
     * 失敗累計、成功清除該帳號計數。
     * @param clientIp 來源 IP（request.getRemoteAddr()）
     * @return {token, user}；失敗回傳 empty（呼叫端統一回「帳號或密碼錯誤」）
     */
    public Optional<Map<String, Object>> login(String identifier, String password, String clientIp) {
        // 測試期（AGRI_LOGIN_LOCK_ADMIN_ONLY=true）：只有科技後台帳號計次鎖定，其餘角色不計帳號與 IP 失敗
        boolean limited = !settings.isLoginLockAdminOnly() || isAdminIdentifier(identifier);
        if (limited) loginRateLimiter.checkAllowed(identifier, clientIp);
        Optional<Principal> p = authenticate(identifier, password);
        if (limited) {
            if (p.isPresent()) {
                loginRateLimiter.recordSuccess(identifier);
            } else {
                loginRateLimiter.recordFailure(identifier, clientIp);
            }
        }
        return p.map(x -> buildLoginResponse(x, false));
    }

    private boolean isAdminIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return false;
        return userRepository.findById(identifier.trim())
                .map(u -> AuthContext.ROLE_ADMIN.equals(u.getRole()))
                .orElse(false);
    }

    Optional<Principal> authenticate(String identifier, String password) {
        if (identifier == null || identifier.isBlank() || password == null || password.isEmpty() || password.length() > 256) {
            passwordHasher.burnTime(password);
            return Optional.empty();
        }
        String id = identifier.trim();

        List<Principal> candidates = new ArrayList<>();
        userRepository.findById(id).ifPresent(u -> candidates.add(fromUser(u)));
        // 自助註冊的農場主以手機號碼（純數字）為帳號：輸入含 - 或空白時也找得到
        String digits = id.replaceAll("[\\s-]", "");
        if (!digits.equals(id) && digits.matches("\\d{10}")) {
            userRepository.findById(digits).ifPresent(u -> candidates.add(fromUser(u)));
        }

        Set<String> seenWorkers = new HashSet<>();
        List<Worker> workers = new ArrayList<>();
        workerRepository.findById(id.toUpperCase(Locale.ROOT)).ifPresent(workers::add);
        workers.addAll(workerRepository.findAllByArcNumber(id));
        workers.addAll(workerRepository.findAllByPassportNumber(id));
        workers.addAll(workerRepository.findAllByPhone(id));
        for (Worker w : workers) {
            if (seenWorkers.add(w.getId())) candidates.add(fromWorker(w));
        }

        if (candidates.isEmpty()) {
            passwordHasher.burnTime(password);
            return Optional.empty();
        }
        for (Principal p : candidates) {
            String stored = p.user() != null ? p.user().getPassword() : p.worker().getPasswordHash();
            if (passwordHasher.matches(password, stored)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * demo 模式免密登入（僅 demo 模式；非 demo 由 Controller 回 404）
     */
    public Map<String, Object> demoLogin(String role, String refId) {
        if (role == null || !DEMO_DEFAULT_REF.containsKey(role)) {
            throw ApiException.badRequest("role 必須為 admin_tech / farmer / worker_foreign / worker_local");
        }
        String ref = (refId == null || refId.isBlank()) ? DEMO_DEFAULT_REF.get(role) : refId.trim();

        Principal principal = userRepository.findByRoleAndRefId(role, ref).stream()
                .findFirst().map(this::fromUser).orElse(null);
        if (principal == null && (AuthContext.ROLE_WORKER_FOREIGN.equals(role) || AuthContext.ROLE_WORKER_LOCAL.equals(role))) {
            principal = workerRepository.findById(ref.toUpperCase(Locale.ROOT))
                    .map(this::fromWorker)
                    .filter(p -> p.role().equals(role))
                    .orElse(null);
        }
        if (principal == null) {
            throw ApiException.badRequest("查無對應的示範帳號：" + role + " / " + ref);
        }
        return buildLoginResponse(principal, true);
    }

    /** GET /api/auth/me：由 token 還原使用者物件 */
    public Map<String, Object> me(AuthContext ctx) {
        Principal p = userRepository.findById(ctx.sub()).map(this::fromUser)
                .or(() -> workerRepository.findById(ctx.sub()).map(this::fromWorker))
                .orElseThrow(ApiException::unauthorized);
        return buildUser(p, ctx.demo());
    }

    // =========================================================================
    // 自助註冊（目前只開放農場主；工人須經合作社查驗證件後由後台建檔）
    // =========================================================================

    /**
     * 農場主自助註冊：建立 farmers 與 users（帳號＝手機號碼純數字、role=farmer），成功即回登入結果。
     * 必填 name、phone（台灣手機 09 開頭 10 碼）、password（8～128 字元）、address、pdpaConsent=true；
     * farmName、farmType 選填。手機已註冊 → 409。同一 IP 每小時 10 次（RegisterRateLimiter）。
     */
    @Transactional
    public Map<String, Object> registerFarmer(Map<String, Object> req, String clientIp) {
        registerRateLimiter.acquire(clientIp);
        String name = trimmed(req.get("name"));
        String phone = trimmed(req.get("phone"));
        String password = req.get("password") == null ? null : req.get("password").toString();
        String address = trimmed(req.get("address"));
        String farmName = trimmed(req.get("farmName"));
        String farmType = trimmed(req.get("farmType"));

        if (name == null || name.length() > 50) throw ApiException.badRequest("請填寫姓名（50 字以內）");
        String mobile = phone == null ? "" : phone.replaceAll("[\\s-]", "");
        if (!mobile.matches("09\\d{8}")) throw ApiException.badRequest("請填寫正確的手機號碼（09 開頭共 10 碼）");
        validateNewPassword(password);
        if (address == null || address.length() > 255) throw ApiException.badRequest("請填寫農地地址（255 字以內）");
        if (farmName != null && farmName.length() > 100) throw ApiException.badRequest("農場名稱請在 100 字以內");
        if (farmType != null && farmType.length() > 50) throw ApiException.badRequest("農場類型請在 50 字以內");
        if (!Boolean.TRUE.equals(req.get("pdpaConsent"))) throw ApiException.badRequest("請先閱讀並同意個人資料蒐集告知事項");

        if (userRepository.existsById(mobile) || farmerRepository.findByPhone(mobile).isPresent()) {
            throw ApiException.conflict("此手機號碼已註冊，請直接登入");
        }

        // 農場主編號：farmers 表與既有 farmer 帳號的 refId（種子帳號 F001、F002 只存在 users）一起取最大值
        Set<String> used = new HashSet<>();
        farmerRepository.findAll().forEach(f -> used.add(f.getId()));
        userRepository.findAll().forEach(u -> { if (u.getRefId() != null) used.add(u.getRefId()); });
        String farmerId = IdGenerator.next("F", 3, used, used::contains);

        farmerRepository.save(new Farmer(farmerId, farmName != null ? farmName : name, mobile, address, null, farmType));
        User user = new User(mobile, passwordHasher.hash(password), name, AuthContext.ROLE_FARMER, farmerId,
                (farmName != null ? farmName : "自助註冊") + " 農場主");
        userRepository.save(user);
        return buildLoginResponse(fromUser(user), false);
    }

    private static String trimmed(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // =========================================================================
    // 密碼管理
    // =========================================================================

    /** 本人變更密碼 */
    public void changePassword(AuthContext ctx, String oldPassword, String newPassword) {
        validateNewPassword(newPassword);
        Optional<User> u = userRepository.findById(ctx.sub());
        if (u.isPresent()) {
            if (!passwordHasher.matches(oldPassword, u.get().getPassword())) throw ApiException.badRequest("原密碼錯誤");
            u.get().setPassword(passwordHasher.hash(newPassword));
            u.get().bumpTokenVersion();   // 舊 token（含本次請求所用的）立即失效
            userRepository.save(u.get());
            return;
        }
        Worker w = workerRepository.findById(ctx.sub()).orElseThrow(ApiException::unauthorized);
        if (!passwordHasher.matches(oldPassword, w.getPasswordHash())) throw ApiException.badRequest("原密碼錯誤");
        w.setPasswordHash(passwordHasher.hash(newPassword));
        w.bumpTokenVersion();
        workerRepository.save(w);
    }

    /** 管理員重設帳號（users 帳號或農工工號）密碼 */
    public void resetPassword(String identifier, String newPassword) {
        if (identifier == null || identifier.isBlank()) throw ApiException.badRequest("identifier 不得為空");
        validateNewPassword(newPassword);
        String id = identifier.trim();
        Optional<User> u = userRepository.findById(id);
        if (u.isPresent()) {
            u.get().setPassword(passwordHasher.hash(newPassword));
            u.get().bumpTokenVersion();   // 該帳號既有 token 立即失效
            userRepository.save(u.get());
            return;
        }
        Worker w = workerRepository.findById(id.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("查無帳號：" + id));
        w.setPasswordHash(passwordHasher.hash(newPassword));
        w.bumpTokenVersion();
        workerRepository.save(w);
    }

    private static void validateNewPassword(String pw) {
        if (pw == null || pw.length() < 8 || pw.length() > 128) {
            throw ApiException.badRequest("新密碼長度需為 8～128 字元");
        }
    }

    // =========================================================================
    // 使用者物件組裝（規格第 4 節：username, name, role, refId, roleDesc, subRole?, demo?, workerProfile?）
    // =========================================================================

    private Map<String, Object> buildLoginResponse(Principal p, boolean demo) {
        int ver = p.user() != null ? p.user().getTokenVersion() : p.worker().getTokenVersion();
        String token = tokenService.issueWithVersion(p.username(), p.role(), p.refId(), demo, ver);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("token", token);
        res.put("user", buildUser(p, demo));
        return res;
    }

    private Map<String, Object> buildUser(Principal p, boolean demo) {
        Map<String, Object> user = new LinkedHashMap<>();
        Worker w = p.worker();
        if (w == null && p.refId() != null && p.user() != null) {
            w = workerRepository.findById(p.refId()).orElse(null);
        }
        user.put("username", p.username());
        if (p.user() != null) {
            user.put("name", p.user().getName());
            user.put("roleDesc", p.user().getRoleDesc());
        } else {
            user.put("name", workerDisplayName(w));
            user.put("roleDesc", workerRoleDesc(w));
        }
        user.put("role", p.role());
        user.put("refId", p.refId());
        if (AuthContext.ROLE_WORKER_LOCAL.equals(p.role()) && w != null) {
            user.put("subRole", workerSubRole(w));
        }
        if (demo) user.put("demo", true);
        if (w != null) {
            user.put("workerProfile", w); // arcNumber / passportNumber 由 Jackson 序列化器遮罩，passwordHash 不輸出
        }
        return user;
    }

    private Principal fromUser(User u) {
        return new Principal(u.getUsername(), u.getRole(), u.getRefId(), u, null);
    }

    private Principal fromWorker(Worker w) {
        String role = "tier1_foreign".equals(w.getTier()) ? AuthContext.ROLE_WORKER_FOREIGN : AuthContext.ROLE_WORKER_LOCAL;
        return new Principal(w.getId(), role, w.getId(), null, w);
    }

    private static String workerDisplayName(Worker w) {
        if (w == null) return "";
        if (w.getChineseName() == null || w.getChineseName().equals(w.getName())) return w.getName();
        return w.getChineseName() + " (" + w.getName() + ")";
    }

    /** 本地工子角色：machinery（機耕/飛手）、foreman（帶隊領班）、labor（勞務員/兼職） */
    public static String workerSubRole(Worker w) {
        if ("tier3_turnkey".equals(w.getTier())) return "machinery";
        if (w.isLeader()) return "foreman";
        return "labor";
    }

    private static String workerRoleDesc(Worker w) {
        if (w == null) return "";
        if ("tier1_foreign".equals(w.getTier())) return "外籍合法外展農工 (Tier 1)";
        if ("tier3_turnkey".equals(w.getTier())) return "本國專業機耕師 (Tier 3)";
        if (w.isLeader()) return "本國外展帶隊領班 (Tier 2)";
        if (w.getCurrentTask() != null && w.getCurrentTask().contains("合作社在籍")) {
            return laborRoleDesc();
        }
        return "本國彈性兼職農工 (Tier 2)";
    }

    /** 合作社在籍農業勞務員說明文字（薪資數字取自常數） */
    public static String laborRoleDesc() {
        // TODO 待農業部確認適用對象（就業獎勵金 8,800）
        return "合作社在籍農業勞務員 (月保障底薪 $" + LaborConstants.formatMoney(LaborConstants.MIN_MONTHLY_WAGE)
                + " + 農業部就業獎勵金 $" + LaborConstants.formatMoney(LaborConstants.AGRI_EMPLOYMENT_BONUS) + ")";
    }

    // =========================================================================
    // demo 示範帳號清單（僅 demo 模式；不含密碼、居留證號、護照號）
    // =========================================================================

    public Map<String, List<Map<String, String>>> getQuickProfiles() {
        if (!settings.isDemoEnabled()) {
            throw ApiException.notFound("NOT_FOUND");
        }
        return Map.of(
            "farmers", List.of(
                Map.of("label", "陳建龍 (阿龍班長) - 八德有機蔬菜班", "username", "farmer", "refId", "F001", "note", "契作面積 5.0 分地・早鳥工時包預約中"),
                Map.of("label", "李春發 (春發伯) - 宜蘭三星青蔥專班", "username", "farmer_yilan", "refId", "F002", "note", "契作面積 8.2 分地・跨區調度專線")
            ),
            "foreignWorkers", List.of(
                Map.of("label", "🇻🇳 阮文雄 (Nguyễn Văn Hùng) - 工班A組長", "id", "W001", "nationality", "越南"),
                Map.of("label", "🇮🇩 阿迪 (Agus Santoso) - 工班B組長", "id", "W006", "nationality", "印尼"),
                Map.of("label", "🇹🇭 宋猜 (Somchai Prasert) - 工班D組", "id", "W020", "nationality", "泰國"),
                Map.of("label", "🇵🇭 瑪麗亞 (Maria Santos) - 工班B組", "id", "W023", "nationality", "菲律賓")
            ),
            "localWorkers", List.of(
                Map.of("label", "🇹🇼 林小祥 (外展帶隊領班) - 帶領6人本國工班", "id", "W026", "phone", "0918-668-999", "tier", "Tier 2 帶隊領班", "subRole", "foreman"),
                Map.of("label", "🇹🇼 陳建宏 (專業機耕/飛手) - 大疆T50巡檢代噴", "id", "W012", "phone", "0988-333-444", "tier", "Tier 3 專業代耕", "subRole", "machinery"),
                Map.of("label", "🇹🇼 張天成 (資深機耕師) - 80馬力曳引機手", "id", "W025", "phone", "0933-555-666", "tier", "Tier 3 專業代耕", "subRole", "machinery"),
                Map.of("label", "🇹🇼 張志明 (合作社農業勞務員)", "id", "W030", "phone", "0975-123-456", "tier", "Tier 2 農業勞務員", "subRole", "labor"),
                Map.of("label", "🇹🇼 王秀英 (中高齡) - 八德葉菜採收", "id", "W031", "phone", "0921-310-031", "tier", "Tier 2 中高齡", "subRole", "labor"),
                Map.of("label", "🇹🇼 蔡佩珊 (學生打工) - 枋山芒果暑期採收", "id", "W036", "phone", "0958-360-036", "tier", "Tier 2 學生打工", "subRole", "labor")
            )
        );
    }
}
