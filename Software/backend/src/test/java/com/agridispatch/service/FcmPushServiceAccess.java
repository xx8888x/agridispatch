package com.agridispatch.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/** 測試用：開放 FcmPushService 的套件內方法給 com.agridispatch 的測試 */
public final class FcmPushServiceAccess {
    private FcmPushServiceAccess() {}

    public static String jwt(ObjectMapper mapper, String serviceAccountJson) throws Exception {
        return FcmPushService.signedJwt(mapper, FcmPushService.parseServiceAccount(mapper, serviceAccountJson), Instant.now());
    }

    public static Map<String, Object> message(String token, String title, String body, String channel) {
        return FcmPushService.message(token, title, body, channel);
    }
}
