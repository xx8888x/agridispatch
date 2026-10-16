package com.agridispatch.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 鄉鎮市區座標：把自由文字地址（農場作業地點、工人居住地）比對成鄉鎮，並計算兩鄉鎮代表點的直線距離。
 * 座標表 taiwan-towns.csv 與前端 js/taiwan-towns.js 同源；比對規則與前端 AgriWeather.matchLocation 一致。
 * 距離是鄉鎮代表點的直線距離（公里），只用來排序「住得近不近」，不是實際車程。
 */
@Component
public class TownGeo {

    /** 比對到的鄉鎮 */
    public record Town(String county, String town, double lat, double lon) {
        public String label() {
            return county + town;
        }
    }

    private final List<Town> towns = new ArrayList<>();
    private final Set<String> counties = new LinkedHashSet<>();

    public TownGeo() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("taiwan-towns.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split(",");
                towns.add(new Town(p[0], p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3])));
                counties.add(p[0]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("讀取 taiwan-towns.csv 失敗", e);
        }
    }

    /**
     * 從自由文字比對鄉鎮：先比「縣市＋鄉鎮全名」，再比鄉鎮全名，最後比簡稱（須唯一）；比不到回 null。
     * 例：「宜蘭縣三星鄉大隱村…」→ 宜蘭縣三星鄉；「枋山愛文芒果合作園區」→ 屏東縣枋山鄉。
     */
    public Town match(String text) {
        if (text == null || text.isBlank()) return null;
        String s = text.replace('臺', '台');
        List<Town> hit = towns.stream().filter(t -> s.contains(t.county()) && s.contains(t.town())).toList();
        if (hit.isEmpty()) {
            // 「苗栗卓蘭…」這類省略縣／市字的寫法：開頭兩字視為縣市並從字串移除，避免「苗栗」誤配苗栗市
            List<String> cs = counties.stream().filter(s::contains).toList();
            if (cs.isEmpty()) cs = counties.stream().filter(c -> s.startsWith(c.substring(0, 2))).toList();
            String rest = cs.isEmpty() ? s : s.replace(cs.get(0), "").replaceFirst("^" + cs.get(0).substring(0, 2), "");
            List<String> pool = cs;
            List<Town> scope = pool.isEmpty() ? towns : towns.stream().filter(t -> pool.contains(t.county())).toList();
            hit = scope.stream().filter(t -> rest.contains(t.town())).toList();
            if (hit.isEmpty()) {
                List<Town> byShort = scope.stream()
                        .filter(t -> shortName(t.town()).length() >= 2 && rest.contains(shortName(t.town()))).toList();
                if (byShort.size() == 1) hit = byShort;
            }
        }
        // 取鄉鎮名最長者（「新竹市」「新竹縣」都含「竹」之類的情況，讓較具體的優先）
        return hit.stream().max(java.util.Comparator.comparingInt(t -> t.town().length())).orElse(null);
    }

    private static String shortName(String town) {
        return town.length() > 2 ? town.replaceFirst("[鄉鎮市區]$", "") : town;
    }

    /** 兩鄉鎮代表點的直線距離（公里，一位小數；haversine） */
    public static double distanceKm(Town a, Town b) {
        double r = 6371.0;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double km = 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
        return Math.round(km * 10) / 10.0;
    }
}
