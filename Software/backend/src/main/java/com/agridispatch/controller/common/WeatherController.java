package com.agridispatch.controller.common;

import com.agridispatch.service.WeatherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 天候戰情：全台鄉鎮市區天氣（中央氣象署代理，授權碼不出後端）。
 * 需登入（任一角色）；未設定 CWA_API_KEY 回 503，前端 demo 模式改用 Open-Meteo。
 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/towns")
    public ResponseEntity<?> towns(@RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.ok(Map.of("success", true, "data", weatherService.getTowns(force)));
    }
}
