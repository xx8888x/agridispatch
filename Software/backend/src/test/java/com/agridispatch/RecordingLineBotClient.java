package com.agridispatch;

import com.agridispatch.config.AgriSettings;
import com.agridispatch.service.LineBotClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 測試用 LINE Bot 客戶端：不連線，只記錄推播呼叫（Mockito 目前不支援 Java 26，改用子類別替身）
 */
public class RecordingLineBotClient extends LineBotClient {

    /** 一次 notify 呼叫 */
    public record Call(String channel, String type, Map<String, Object> params, List<Map<String, String>> recipients) {}

    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    public final List<String> broadcasts = Collections.synchronizedList(new ArrayList<>());

    public RecordingLineBotClient(AgriSettings settings, ObjectMapper objectMapper) {
        super(settings, objectMapper);
    }

    @Override
    public Map<String, Object> notify(String channel, String type, Map<String, Object> params, List<Map<String, String>> recipients) {
        calls.add(new Call(channel, type, params, recipients));
        return Map.of("success", true, "result", "recorded");
    }

    @Override
    public Map<String, Object> broadcastFarmer(String text) {
        broadcasts.add(text);
        return Map.of("success", true, "result", "recorded");
    }

    @Override
    public void linkRichMenu(String channel, String lineUserId, String menuKey) {
        // 測試不連 LINE Bot
    }

    public void clear() {
        calls.clear();
        broadcasts.clear();
    }

    public List<Call> of(String channel, String type) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.channel().equals(channel) && c.type().equals(type)).toList();
        }
    }

    /** 某帳號某種通知的全部收件人：lineUserId → lang */
    public Map<String, String> recipients(String channel, String type) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Call c : of(channel, type)) {
            for (Map<String, String> r : c.recipients()) out.put(r.get("lineUserId"), r.get("lang"));
        }
        return out;
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public RecordingLineBotClient recordingLineBotClient(AgriSettings settings, ObjectMapper objectMapper) {
            return new RecordingLineBotClient(settings, objectMapper);
        }
    }
}
