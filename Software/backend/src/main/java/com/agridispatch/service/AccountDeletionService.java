package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.entity.AccountDeletionRequest;
import com.agridispatch.entity.Farmer;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.User;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.*;
import com.agridispatch.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 刪除帳號申請（Google Play 刪除帳號規定；資料安全表單草稿 10_ 第壹章第 2 項）
 *
 * 申請：已登入者在 App「我的帳號」申請（app），或免登入在網頁申請（web，只收姓名、電話、帳號，回應不透露帳號是否存在）。
 * 處理：一律由合作社在科技後台核對身分後完成或不受理，系統不自動刪除。
 * 完成時做的事：
 *   共同  解除 LINE 兩個官方帳號的綁定、刪除 App 推播裝置
 *   農場主  刪除登入帳號（users），農場主與農場資料的姓名、電話、地址改為「（已刪除）」；有未完成的訂單時不能刪
 *   工人  移除登入密碼並讓已登入的裝置失效；工人的聘僱、出勤、薪資、保險資料是合作社（法定雇主）的人事紀錄，依法保存，不在此刪除
 *   保留  訂單、付款、出勤、工資紀錄依勞基法與稅法保存（期間【待確認】）
 * 後台帳號（admin_tech）不受理，由系統管理員處理。
 * demo 模式（路演）只更新申請狀態、不真的刪除，避免共用的示範帳號被刪掉後無法示範登入（與前端 Mock 一致）。
 */
@Service
public class AccountDeletionService {

    static final String REDACTED = "（已刪除）";
    static final int TEXT_MAX = 500;
    /** 網頁申請：同一 IP 每小時上限 */
    static final int WEB_PER_HOUR = 5;
    private static final Set<String> ACTIVE_ORDER = Set.of("pending", "confirmed", "in_progress");

    private final AccountDeletionRequestRepository repository;
    private final UserRepository userRepository;
    private final WorkerRepository workerRepository;
    private final FarmerRepository farmerRepository;
    private final FarmRepository farmRepository;
    private final OrderRepository orderRepository;
    private final PushTokenRepository pushTokenRepository;
    private final LineBindingService lineBindingService;
    private final AgriSettings settings;
    private final Map<String, Deque<Long>> webHits = new ConcurrentHashMap<>();

    public AccountDeletionService(AccountDeletionRequestRepository repository, UserRepository userRepository,
                                  WorkerRepository workerRepository, FarmerRepository farmerRepository,
                                  FarmRepository farmRepository, OrderRepository orderRepository,
                                  PushTokenRepository pushTokenRepository, LineBindingService lineBindingService,
                                  AgriSettings settings) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.workerRepository = workerRepository;
        this.farmerRepository = farmerRepository;
        this.farmRepository = farmRepository;
        this.orderRepository = orderRepository;
        this.pushTokenRepository = pushTokenRepository;
        this.lineBindingService = lineBindingService;
        this.settings = settings;
    }

    // =========================================================================
    // 使用者
    // =========================================================================

    /** App 內申請：每個帳號同時只能有一筆待處理 */
    public Map<String, Object> requestFromApp(AuthContext ctx, String reason) {
        // 只看實際角色（isAdmin() 在 demo 模式對所有示範 token 都成立）
        if (AuthContext.ROLE_ADMIN.equals(ctx.role())) throw ApiException.badRequest("後台管理帳號請洽系統管理員處理");
        if (latestPending(ctx.sub()).isPresent()) throw ApiException.conflict("您已送出刪除帳號申請，合作社處理中");
        AccountDeletionRequest r = new AccountDeletionRequest();
        r.setId(nextId());
        r.setSource("app");
        r.setUsername(ctx.sub());
        r.setRefId(ctx.ref());
        r.setRole(ctx.role());
        r.setReason(text(reason, "申請原因"));
        return view(repository.save(r), false);
    }

    /** 本人最近一筆申請（沒有回 null） */
    public Map<String, Object> mine(AuthContext ctx) {
        return repository.findByUsernameOrderByCreatedAtDesc(ctx.sub()).stream()
                .filter(r -> "app".equals(r.getSource()))
                .findFirst().map(r -> view(r, false)).orElse(null);
    }

    /** 本人撤回待處理的申請 */
    public Map<String, Object> withdraw(AuthContext ctx) {
        AccountDeletionRequest r = latestPending(ctx.sub())
                .orElseThrow(() -> ApiException.notFound("沒有待處理的刪除帳號申請"));
        r.setStatus(AccountDeletionRequest.WITHDRAWN);
        r.setDecidedAt(LocalDateTime.now());
        r.setDecidedBy(ctx.sub());
        return view(repository.save(r), false);
    }

    /**
     * 免登入網頁申請：不查帳號是否存在、回應一律相同，避免被用來探測誰有帳號。
     * 同一 IP 每小時最多 WEB_PER_HOUR 次（不論成功失敗）。
     */
    public void requestFromWeb(String name, String phone, String account, String reason, String clientIp) {
        throttle(clientIp);
        String n = trim(name);
        if (n == null || n.length() > 50) throw ApiException.badRequest("請填寫姓名（50 字以內）");
        String p = phone == null ? "" : phone.replaceAll("[\\s()-]", "");
        if (!p.matches("\\+?\\d{8,15}")) throw ApiException.badRequest("請填寫可以聯絡到您的電話");
        String a = trim(account);
        if (a != null && a.length() > 64) throw ApiException.badRequest("帳號請在 64 字以內");
        AccountDeletionRequest r = new AccountDeletionRequest();
        r.setId(nextId());
        r.setSource("web");
        r.setContactName(n);
        r.setContactPhone(p);
        r.setAccountHint(a);
        r.setReason(text(reason, "申請原因"));
        repository.save(r);
    }

    // =========================================================================
    // 科技後台
    // =========================================================================

    /** 全部申請（新的在前）；待處理的附上「帳號找不到」「有未完成訂單」等提醒 */
    public List<Map<String, Object>> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(r -> view(r, true)).toList();
    }

    /**
     * 完成刪除。app 申請刪申請人本人；web 申請由後台在 identifier 指定核對過的帳號（users 帳號或工號）。
     */
    @Transactional
    public Map<String, Object> complete(String id, String identifier, String note, AuthContext admin) {
        AccountDeletionRequest r = pending(id);
        String target = "app".equals(r.getSource()) ? r.getUsername() : trim(identifier);
        if (target == null) throw ApiException.badRequest("網頁申請請先核對身分，並填入要刪除的帳號或工號");
        Account acc = resolve(target).orElseThrow(() -> ApiException.notFound("查無帳號：" + target));
        if (AuthContext.ROLE_ADMIN.equals(acc.role())) throw ApiException.badRequest("後台管理帳號不在此刪除");
        if (acc.isFarmer()) {
            List<String> active = activeOrders(acc.refId());
            if (!active.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "尚有未完成的訂單（" + String.join("、", active) + "），請先完工或取消再刪除");
            }
        }

        if (settings.isDemoEnabled()) {
            return finish(r, acc, note, admin, "示範模式：只更新申請狀態，示範帳號不會真的刪除");
        }

        List<String> done = new ArrayList<>();
        int line = lineBindingService.unbindAccount(acc.username(), acc.refId(), acc.role());
        if (line > 0) done.add("解除 LINE 綁定 " + line + " 筆");
        var tokens = pushTokenRepository.findByUsername(acc.username());
        if (!tokens.isEmpty()) {
            pushTokenRepository.deleteAll(tokens);
            done.add("刪除推播裝置 " + tokens.size() + " 台");
        }
        if (acc.isFarmer()) {
            userRepository.deleteById(acc.username());
            done.add("刪除登入帳號");
            if (acc.refId() != null) {
                farmerRepository.findById(acc.refId()).ifPresent(f -> {
                    redact(f);
                    farmerRepository.save(f);
                    done.add("農場主資料去識別化");
                });
                var farms = farmRepository.findByFarmerIdOrderByCreatedAtAscIdAsc(acc.refId());
                farms.forEach(f -> {
                    f.setName(REDACTED);
                    f.setContactName(REDACTED);
                    f.setPhone(REDACTED);
                    f.setAddress(REDACTED);
                });
                farmRepository.saveAll(farms);
                if (!farms.isEmpty()) done.add("農場資料去識別化 " + farms.size() + " 筆");
            }
            done.add("訂單與付款紀錄依法保留");
        } else {
            Worker w = acc.worker();
            w.setPasswordHash(null);
            w.bumpTokenVersion();
            workerRepository.save(w);
            done.add("移除登入密碼、已登入裝置失效");
            done.add("聘僱、出勤、薪資與保險紀錄由合作社依法保存");
        }

        return finish(r, acc, note, admin, String.join("；", done));
    }

    private Map<String, Object> finish(AccountDeletionRequest r, Account acc, String note, AuthContext admin, String summary) {
        r.setStatus(AccountDeletionRequest.COMPLETED);
        r.setUsername(acc.username());
        r.setRefId(acc.refId());
        r.setRole(acc.role());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecidedBy(admin.sub());
        r.setDecisionNote(text(note, "備註"));
        r.setResultSummary(summary);
        return view(repository.save(r), true);
    }

    /** 不受理（例如無法核對身分）：須填原因 */
    public Map<String, Object> reject(String id, String note, AuthContext admin) {
        AccountDeletionRequest r = pending(id);
        String n = text(note, "不受理原因");
        if (n == null) throw ApiException.badRequest("請填寫不受理原因");
        r.setStatus(AccountDeletionRequest.REJECTED);
        r.setDecidedAt(LocalDateTime.now());
        r.setDecidedBy(admin.sub());
        r.setDecisionNote(n);
        return view(repository.save(r), true);
    }

    // =========================================================================
    // 內部
    // =========================================================================

    /** 要刪的平台帳號：users 帳號（農場主、後台）或農工工號 */
    record Account(String username, String role, String refId, User user, Worker worker) {
        boolean isFarmer() { return user != null && AuthContext.ROLE_FARMER.equals(role); }
    }

    Optional<Account> resolve(String identifier) {
        Optional<User> u = userRepository.findById(identifier);
        if (u.isPresent()) return Optional.of(new Account(u.get().getUsername(), u.get().getRole(), u.get().getRefId(), u.get(), null));
        return workerRepository.findById(identifier.toUpperCase(Locale.ROOT))
                .map(w -> new Account(w.getId(), "tier1_foreign".equals(w.getTier())
                        ? AuthContext.ROLE_WORKER_FOREIGN : AuthContext.ROLE_WORKER_LOCAL, w.getId(), null, w));
    }

    private List<String> activeOrders(String farmerId) {
        if (farmerId == null) return List.of();
        return orderRepository.findAll().stream()
                .filter(o -> farmerId.equalsIgnoreCase(o.getFarmerId()) && ACTIVE_ORDER.contains(o.getStatus()))
                .map(Order::getId).sorted().toList();
    }

    private static void redact(Farmer f) {
        f.setName(REDACTED);
        f.setPhone(REDACTED);
        f.setAddress(REDACTED);
        f.setTaxId(null);
    }

    private Optional<AccountDeletionRequest> latestPending(String username) {
        return repository.findByUsernameOrderByCreatedAtDesc(username).stream()
                .filter(r -> AccountDeletionRequest.PENDING.equals(r.getStatus())).findFirst();
    }

    private AccountDeletionRequest pending(String id) {
        AccountDeletionRequest r = repository.findById(id == null ? "" : id)
                .orElseThrow(() -> ApiException.notFound("查無此申請"));
        if (!AccountDeletionRequest.PENDING.equals(r.getStatus())) throw ApiException.conflict("此申請已處理");
        return r;
    }

    private String nextId() {
        String prefix = "DR-" + LocalDate.now().getYear() + "-";
        return IdGenerator.next(prefix, 3, repository.findIdsByPrefix(prefix), repository::existsById);
    }

    private Map<String, Object> view(AccountDeletionRequest r, boolean admin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("source", r.getSource());
        m.put("status", r.getStatus());
        m.put("createdAt", r.getCreatedAt());
        m.put("decidedAt", r.getDecidedAt());
        m.put("decisionNote", r.getDecisionNote());
        m.put("reason", r.getReason());
        if (!admin) return m;
        m.put("username", r.getUsername());
        m.put("refId", r.getRefId());
        m.put("role", r.getRole());
        m.put("contactName", r.getContactName());
        m.put("contactPhone", r.getContactPhone());
        m.put("accountHint", r.getAccountHint());
        m.put("decidedBy", r.getDecidedBy());
        m.put("resultSummary", r.getResultSummary());
        if (AccountDeletionRequest.PENDING.equals(r.getStatus()) && r.getUsername() != null) {
            Optional<Account> acc = resolve(r.getUsername());
            m.put("accountName", acc.map(a -> a.user() != null ? a.user().getName() : a.worker().getName()).orElse(null));
            List<String> warn = new ArrayList<>();
            if (acc.isEmpty()) warn.add("帳號已不存在");
            else if (acc.get().isFarmer()) {
                List<String> active = activeOrders(acc.get().refId());
                if (!active.isEmpty()) warn.add("尚有未完成的訂單：" + String.join("、", active));
            }
            m.put("warnings", warn);
        } else if (AccountDeletionRequest.PENDING.equals(r.getStatus()) && r.getAccountHint() != null) {
            // 網頁申請：使用者填的帳號若對得到，提示後台（仍須核對姓名、電話才能刪）
            resolve(r.getAccountHint()).ifPresent(a -> {
                m.put("suggestedAccount", a.username());
                m.put("accountName", a.user() != null ? a.user().getName() : a.worker().getName());
            });
        }
        return m;
    }

    private void throttle(String ip) {
        long now = System.currentTimeMillis(), window = 3_600_000L;
        Deque<Long> q = webHits.computeIfAbsent(ip == null ? "" : ip.trim(), k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= now - window) q.pollFirst();
            if (q.size() >= WEB_PER_HOUR) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "送出次數過多，請稍後再試");
            q.addLast(now);
        }
        if (webHits.size() > 10_000) {
            webHits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    Long last = e.getValue().peekLast();
                    return last == null || last <= now - window;
                }
            });
        }
    }

    /** 測試用 */
    void resetThrottle() {
        webHits.clear();
    }

    private static String text(String s, String label) {
        String t = trim(s);
        if (t != null && t.length() > TEXT_MAX) throw ApiException.badRequest(label + "請在 " + TEXT_MAX + " 字以內");
        return t;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
