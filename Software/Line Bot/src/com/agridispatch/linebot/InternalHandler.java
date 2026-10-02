package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智農派工 - Line Bot 內部端點（後端呼叫，規格 05 第 6.3 節）
 *   POST /internal/richmenu/link  {channel, lineUserId, menuKey}
 *   POST /internal/notify         {channel, type, params, recipients:[{lineUserId, lang}]}：依語言分組產生訊息並 multicast（規格第柒章）
 *   POST /internal/push           {channel, to:[...], messages:[...]} 或 {channel:"farmer", broadcast:true, messages:[...]}
 *                                 （broadcast 只允許農場主帳號；工作者帳號的工作通知只能推給名單上的人）
 * 以 X-Internal-Key（AGRI_LINEBOT_INTERNAL_KEY）constant-time 比對；未設定金鑰時整組停用（403）。
 *
 * 選單代碼對應的 richMenuId 由 RichMenuUploader 上傳後記錄（RichMenus）；Mock 模式只記錄不呼叫 LINE。
 * 圖文選單回應 result：linked／unlinked（guest 選單＝回到帳號預設）／mock／missing（尚未上傳）／failed。
 */
public class InternalHandler implements HttpHandler {

    static final Set<String> MENU_KEYS = Set.of("farmer_guest", "farmer_main", "worker_guest",
            "worker_main_zh", "worker_main_vi", "worker_main_id");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        if (Config.INTERNAL_KEY == null) {
            HttpUtils.sendJson(exchange, 403, "{\"success\":false,\"error\":\"內部端點未啟用：請設定 AGRI_LINEBOT_INTERNAL_KEY\"}");
            return;
        }
        String provided = exchange.getRequestHeaders().getFirst("X-Internal-Key");
        if (provided == null || !MessageDigest.isEqual(Config.INTERNAL_KEY.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8))) {
            HttpUtils.sendJson(exchange, 403, "{\"success\":false,\"error\":\"FORBIDDEN\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath().replaceAll("/+$", "");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        switch (path) {
            case "/internal/richmenu/link" -> richMenuLink(exchange, SimpleJson.parseObject(body));
            case "/internal/notify" -> notify(exchange, SimpleJson.parseObject(body));
            case "/internal/push" -> push(exchange, body);
            default -> HttpUtils.sendJson(exchange, 404, "{\"success\":false,\"error\":\"NOT_FOUND\"}");
        }
    }

    private void richMenuLink(HttpExchange exchange, Map<String, Object> f) throws IOException {
        Channel channel = Channel.fromKey(f.get("channel") == null ? null : String.valueOf(f.get("channel")));
        String userId = f.get("lineUserId") == null ? "" : String.valueOf(f.get("lineUserId"));
        String menuKey = f.get("menuKey") == null ? "" : String.valueOf(f.get("menuKey"));
        if (channel == null || userId.isBlank() || !MENU_KEYS.contains(menuKey)
                || !menuKey.startsWith(channel.key + "_")) {
            HttpUtils.sendJson(exchange, 400, "{\"success\":false,\"error\":\"channel、lineUserId 或 menuKey 不正確\"}");
            return;
        }

        String result = RichMenus.link(channel, userId, menuKey);
        boolean linked = "linked".equals(result) || "unlinked".equals(result);
        HttpUtils.sendJson(exchange, 200, "{\"success\":true,\"linked\":" + linked + ",\"result\":\"" + result
                + "\",\"menuKey\":\"" + menuKey + "\"}");
    }

    /** 依收件人語言分組，各組產生一次訊息後 multicast */
    @SuppressWarnings("unchecked")
    private void notify(HttpExchange exchange, Map<String, Object> f) throws IOException {
        Channel channel = Channel.fromKey(f.get("channel") == null ? null : String.valueOf(f.get("channel")));
        String type = f.get("type") == null ? "" : String.valueOf(f.get("type"));
        if (channel == null || !NotifyMessages.supports(channel, type) || !(f.get("recipients") instanceof List)) {
            HttpUtils.sendJson(exchange, 400, "{\"success\":false,\"error\":\"channel、type 或 recipients 不正確\"}");
            return;
        }
        Map<String, Object> params = f.get("params") instanceof Map ? (Map<String, Object>) f.get("params") : Map.of();
        Map<String, List<String>> byLang = new LinkedHashMap<>();
        for (Object o : (List<Object>) f.get("recipients")) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> r = (Map<String, Object>) o;
            String uid = r.get("lineUserId") == null ? "" : String.valueOf(r.get("lineUserId"));
            if (uid.isBlank()) continue;
            String lang = channel == Channel.FARMER ? "zh" : I18n.normalize(r.get("lang") == null ? null : String.valueOf(r.get("lang")));
            byLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(uid);
        }
        int total = 0, sent = 0;
        StringBuilder groups = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byLang.entrySet()) {
            List<String> messages = NotifyMessages.render(channel, type, e.getKey(), params);
            int ok = LineApiClient.of(channel).multicast(e.getValue(), messages);
            MockOutbox.add(channel, type, e.getKey(), e.getValue(), messages.isEmpty() ? null : messages.get(0));
            total += e.getValue().size();
            sent += ok;
            if (groups.length() > 0) groups.append(",");
            groups.append("\"").append(e.getKey()).append("\":").append(e.getValue().size());
        }
        String result = channel.isMock() ? "mock" : (sent == total ? "sent" : (sent == 0 ? "failed" : "partial"));
        System.out.println("📨 [" + channel.key + "] 推播 " + type + "：" + sent + "/" + total + " 人（" + result + "）語言 {" + groups + "}");
        HttpUtils.sendJson(exchange, 200, "{\"success\":" + (sent == total) + ",\"result\":\"" + result + "\",\"recipients\":" + total
                + ",\"sent\":" + sent + ",\"groups\":{" + groups + "}}");
    }

    /** 原始訊息推播；broadcast 只允許農場主帳號 */
    @SuppressWarnings("unchecked")
    private void push(HttpExchange exchange, String body) throws IOException {
        Map<String, Object> f = SimpleJson.parseObject(body);
        Channel channel = Channel.fromKey(f.get("channel") == null ? null : String.valueOf(f.get("channel")));
        List<String> messages = BackendClient.arrayElements(body, "\"messages\"");
        if (channel == null || messages.isEmpty() || messages.size() > 5) {
            HttpUtils.sendJson(exchange, 400, "{\"success\":false,\"error\":\"channel 或 messages（1～5 則）不正確\"}");
            return;
        }
        if (Boolean.TRUE.equals(f.get("broadcast"))) {
            if (channel != Channel.FARMER) {
                HttpUtils.sendJson(exchange, 400, "{\"success\":false,\"error\":\"工作者帳號禁止 broadcast\"}");
                return;
            }
            boolean ok = LineApiClient.of(channel).broadcast(String.join(",", messages));
            MockOutbox.add(channel, "broadcast", "zh", List.of("*"), messages.get(0));
            HttpUtils.sendJson(exchange, 200, "{\"success\":" + ok + ",\"result\":\"" + (channel.isMock() ? "mock" : ok ? "sent" : "failed") + "\"}");
            return;
        }
        List<String> to = new ArrayList<>();
        if (f.get("to") instanceof List) {
            for (Object o : (List<Object>) f.get("to")) if (o != null && !String.valueOf(o).isBlank()) to.add(String.valueOf(o));
        }
        if (to.isEmpty()) {
            HttpUtils.sendJson(exchange, 400, "{\"success\":false,\"error\":\"to 不得為空\"}");
            return;
        }
        int sent = LineApiClient.of(channel).multicast(to, messages);
        MockOutbox.add(channel, "push", "", to, messages.get(0));
        HttpUtils.sendJson(exchange, 200, "{\"success\":" + (sent == to.size()) + ",\"result\":\"" + (channel.isMock() ? "mock" : sent == to.size() ? "sent" : "failed")
                + "\",\"recipients\":" + to.size() + ",\"sent\":" + sent + "}");
    }
}
