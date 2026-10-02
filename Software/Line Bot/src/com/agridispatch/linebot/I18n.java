package com.agridispatch.linebot;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智農派工 - 工作者帳號多語系文字（規格第玖章）
 * 讀取 i18n/worker_{lang}.properties（UTF-8）；缺鍵或缺檔時退回 zh，並記 log 一次。
 */
public final class I18n {
    private I18n() {}

    public static final Set<String> SUPPORTED = Set.of("zh", "vi", "id");

    private static final Map<String, Properties> bundles = new ConcurrentHashMap<>();
    private static final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    public static String normalize(String lang) {
        return lang != null && SUPPORTED.contains(lang) ? lang : "zh";
    }

    public static String t(String lang, String key) {
        String l = normalize(lang);
        String v = bundle(l).getProperty(key);
        if (v != null) return v;
        if (!"zh".equals(l) && reportedMissing.add(l + ":" + key)) {
            System.err.println("⚠️ [i18n] 缺少 " + l + " 翻譯：" + key + "（暫以中文顯示）");
        }
        v = bundle("zh").getProperty(key);
        return v != null ? v : key;
    }

    private static Properties bundle(String lang) {
        return bundles.computeIfAbsent(lang, I18n::load);
    }

    private static Properties load(String lang) {
        Properties p = new Properties();
        String name = "worker_" + lang + ".properties";
        Path path = Path.of("i18n", name);
        if (!Files.exists(path)) path = Path.of("Software", "Line Bot", "i18n", name);
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (Exception e) {
                System.err.println("⚠️ [i18n] 讀取 " + path + " 失敗：" + e.getMessage());
            }
        } else {
            System.err.println("⚠️ [i18n] 找不到 " + name);
        }
        return p;
    }
}
