package com.agridispatch.linebot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 智農派工 - 圖文選單檢核與上傳工具（規格第捌章）
 *
 * 用法（於 Software/Line Bot 目錄，先 compile）：
 *   java -cp bin com.agridispatch.linebot.RichMenuUploader --check
 *       → 離線檢核 6 份選單（JSON 結構、按鈕動作、底圖尺寸與大小），有問題時結束碼為 1
 *   java -cp bin com.agridispatch.linebot.RichMenuUploader farmer|worker|all [--delete-old]
 *       → 上傳該帳號的選單、設定 guest 選單為帳號預設，richMenuId 記到 rich_menu/richmenu_ids.properties；
 *         Line Bot 會自動讀新檔，已綁定的使用者下一次互動時自動連結到新選單。
 *         --delete-old：上傳成功後刪除該帳號先前記錄的舊選單（刪除後到使用者下次互動前，會先看到預設選單）
 *
 * 選單的 JSON 與底圖由 tools/make_rich_menus.py 產生。Mock 模式的帳號只做檢核、不上傳。
 */
public class RichMenuUploader {
    private static final String API = "https://api.line.me/v2/bot/richmenu";
    private static final String DATA_API = "https://api-data.line.me/v2/bot/richmenu";
    private static final String USER_ALL = "https://api.line.me/v2/bot/user/all/richmenu/";
    private static final int W = 2500, H = 1686;
    private static final long MAX_IMAGE = 1024 * 1024;

    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        Config.loadConfig();
        List<String> a = List.of(args);
        if (a.isEmpty() || a.contains("--check")) {
            List<String> problems = validateAll();
            if (problems.isEmpty()) {
                System.out.println("✅ 6 份圖文選單檢核通過（" + RichMenus.dir().toAbsolutePath() + "）");
            } else {
                problems.forEach(p -> System.out.println("❌ " + p));
            }
            if (a.isEmpty()) {
                System.out.println("\n上傳：java -cp bin com.agridispatch.linebot.RichMenuUploader farmer|worker|all [--delete-old]");
            }
            System.exit(problems.isEmpty() ? 0 : 1);
            return;
        }

        List<Channel> targets = new ArrayList<>();
        if (a.contains("all") || a.contains("farmer")) targets.add(Channel.FARMER);
        if (a.contains("all") || a.contains("worker")) targets.add(Channel.WORKER);
        if (targets.isEmpty()) {
            System.out.println("⚠️ 請指定 farmer、worker 或 all");
            System.exit(1);
            return;
        }
        List<String> problems = validateAll();
        if (!problems.isEmpty()) {
            problems.forEach(p -> System.out.println("❌ " + p));
            System.out.println("檢核未通過，不上傳。請修正後重新執行 python tools/make_rich_menus.py。");
            System.exit(1);
            return;
        }
        boolean deleteOld = a.contains("--delete-old");
        boolean ok = true;
        for (Channel c : targets) {
            ok &= upload(c, deleteOld);
        }
        System.exit(ok ? 0 : 1);
    }

    // =========================================================================
    // 離線檢核
    // =========================================================================

    public static List<String> validateAll() {
        List<String> problems = new ArrayList<>();
        for (Channel c : Channel.values()) {
            for (String key : RichMenus.menusOf(c)) {
                problems.addAll(validate(c, key));
            }
        }
        return problems;
    }

    @SuppressWarnings("unchecked")
    static List<String> validate(Channel channel, String key) {
        List<String> p = new ArrayList<>();
        Path json = RichMenus.dir().resolve(key + ".json");
        Path jpg = RichMenus.dir().resolve(key + ".jpg");
        try {
            if (!Files.exists(json)) {
                p.add(key + ": 找不到 " + json);
                return p;
            }
            Map<String, Object> m = SimpleJson.parseObject(Files.readString(json, StandardCharsets.UTF_8));
            Map<String, Object> size = (Map<String, Object>) m.get("size");
            if (size == null || num(size.get("width")) != W || num(size.get("height")) != H) {
                p.add(key + ": size 須為 2500x1686");
            }
            String bar = String.valueOf(m.get("chatBarText"));
            if (m.get("chatBarText") == null || bar.isBlank() || bar.codePointCount(0, bar.length()) > 14) {
                p.add(key + ": chatBarText 須為 1～14 字");
            }
            Object areasObj = m.get("areas");
            if (!(areasObj instanceof List) || ((List<?>) areasObj).isEmpty() || ((List<?>) areasObj).size() > 20) {
                p.add(key + ": areas 須為 1～20 格");
                return p;
            }
            Set<String> actions = channel == Channel.FARMER ? FarmerDispatcher.ACTIONS : WorkerDispatcher.ACTIONS;
            long covered = 0;
            List<long[]> rects = new ArrayList<>();
            for (Object o : (List<Object>) areasObj) {
                Map<String, Object> area = (Map<String, Object>) o;
                Map<String, Object> b = (Map<String, Object>) area.get("bounds");
                long x = num(b.get("x")), y = num(b.get("y")), w = num(b.get("width")), h = num(b.get("height"));
                if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > W || y + h > H) p.add(key + ": 按鈕範圍超出底圖 " + b);
                for (long[] r : rects) {
                    if (x < r[0] + r[2] && r[0] < x + w && y < r[1] + r[3] && r[1] < y + h) p.add(key + ": 按鈕範圍重疊 " + b);
                }
                rects.add(new long[]{x, y, w, h});
                covered += w * h;

                Map<String, Object> action = (Map<String, Object>) area.get("action");
                if (action == null || !"postback".equals(action.get("type"))) {
                    p.add(key + ": 按鈕須為 postback（規格 5.2）");
                    continue;
                }
                String data = String.valueOf(action.get("data"));
                String act = BotEvent.parsePostback(data).getOrDefault("a", "");
                if (data.length() > 300 || !actions.contains(act)) {
                    p.add(key + ": 不支援的動作 " + data + "（" + channel.key + " 帳號）");
                }
                if ("lang".equals(act) && !I18n.SUPPORTED.contains(BotEvent.parsePostback(data).get("v"))) {
                    p.add(key + ": 語言代碼不正確 " + data);
                }
                Object label = action.get("label");
                if (label != null && String.valueOf(label).codePointCount(0, String.valueOf(label).length()) > 20) {
                    p.add(key + ": label 超過 20 字 " + label);
                }
            }
            if (covered != (long) W * H) p.add(key + ": 按鈕沒有剛好鋪滿底圖（覆蓋 " + covered + " / " + ((long) W * H) + "）");

            if (!Files.exists(jpg)) {
                p.add(key + ": 找不到底圖 " + jpg);
            } else {
                if (Files.size(jpg) > MAX_IMAGE) p.add(key + ": 底圖超過 1MB");
                BufferedImage img = ImageIO.read(jpg.toFile());
                if (img == null || img.getWidth() != W || img.getHeight() != H) p.add(key + ": 底圖須為 2500x1686 JPEG");
            }
        } catch (Exception e) {
            p.add(key + ": 讀取失敗 " + e.getMessage());
        }
        return p;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }

    // =========================================================================
    // 上傳
    // =========================================================================

    static boolean upload(Channel channel, boolean deleteOld) throws Exception {
        if (channel.isMock()) {
            System.out.println("⚠️ " + channel.displayName + " 為本地模擬模式（未填真實 channel access token），只做檢核、不上傳。");
            return true;
        }
        String token = channel.accessToken();
        Map<String, String> created = new LinkedHashMap<>();
        System.out.println("=== " + channel.displayName + " ===");
        for (String key : RichMenus.menusOf(channel)) {
            String json = Files.readString(RichMenus.dir().resolve(key + ".json"), StandardCharsets.UTF_8);
            HttpResponse<String> v = send(token, "POST", API + "/validate", json, "application/json; charset=UTF-8");
            if (v.statusCode() != 200) {
                System.out.println("❌ " + key + " 未通過 LINE 結構驗證：" + v.body());
                rollback(token, created);
                return false;
            }
            HttpResponse<String> c = send(token, "POST", API, json, "application/json; charset=UTF-8");
            if (c.statusCode() != 200) {
                System.out.println("❌ " + key + " 建立失敗 [" + c.statusCode() + "]：" + c.body());
                rollback(token, created);
                return false;
            }
            String id = String.valueOf(SimpleJson.parseObject(c.body()).get("richMenuId"));
            created.put(key, id);
            byte[] img = Files.readAllBytes(RichMenus.dir().resolve(key + ".jpg"));
            HttpResponse<String> u = http.send(HttpRequest.newBuilder(URI.create(DATA_API + "/" + id + "/content"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "image/jpeg")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(img)).build(), HttpResponse.BodyHandlers.ofString());
            if (u.statusCode() != 200) {
                System.out.println("❌ " + key + " 底圖上傳失敗 [" + u.statusCode() + "]：" + u.body());
                rollback(token, created);
                return false;
            }
            System.out.println("✅ " + key + " → " + id);
        }

        String guest = channel.key + "_guest";
        HttpResponse<String> d = send(token, "POST", USER_ALL + created.get(guest), null, null);
        if (d.statusCode() != 200) {
            System.out.println("❌ 設定預設選單失敗 [" + d.statusCode() + "]：" + d.body());
            rollback(token, created);
            return false;
        }
        System.out.println("✅ 帳號預設選單 → " + guest);

        // 記錄 richMenuId（保留另一個帳號的紀錄）
        Path f = RichMenus.idsFile();
        Properties props = new Properties();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        }
        Map<String, String> old = new LinkedHashMap<>();
        for (String key : created.keySet()) {
            String prev = props.getProperty(key);
            if (prev != null && !prev.isBlank()) old.put(key, prev);
            props.setProperty(key, created.get(key));
        }
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            props.store(w, "LINE richMenuId（RichMenuUploader 產生；各環境不同，勿進版控）");
        }
        System.out.println("📝 已寫入 " + f.toAbsolutePath());

        if (!old.isEmpty()) {
            if (deleteOld) {
                for (Map.Entry<String, String> e : old.entrySet()) {
                    HttpResponse<String> del = send(token, "DELETE", API + "/" + e.getValue(), null, null);
                    System.out.println((del.statusCode() == 200 ? "🗑️ 已刪除舊選單 " : "⚠️ 刪除舊選單失敗 ") + e.getKey() + " " + e.getValue());
                }
            } else {
                System.out.println("ℹ️ 舊選單未刪除（" + old.values() + "）；確認新選單正常後可加 --delete-old 重新上傳，或於 LINE 後台刪除。");
            }
        }
        System.out.println("ℹ️ 已綁定的使用者會在下一次與帳號互動時自動連結到新選單。");
        return true;
    }

    private static void rollback(String token, Map<String, String> created) {
        for (String id : created.values()) {
            try {
                send(token, "DELETE", API + "/" + id, null, null);
            } catch (Exception ignored) {
                // 盡力而為
            }
        }
    }

    private static HttpResponse<String> send(String token, String method, String url, String body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
