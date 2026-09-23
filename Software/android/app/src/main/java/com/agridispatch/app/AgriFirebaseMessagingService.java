package com.agridispatch.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/**
 * 智農派工 (AgriDispatch) - FCM (Firebase Cloud Messaging) 雲端推播監聽服務
 * 處理雲端推播 Token 註冊、後台訊息接收與前台橫幅通知呈現
 *
 * 注意：若建置時未提供 app/google-services.json (google-services 外掛未套用，見 app/build.gradle)，
 * FirebaseApp 不會被自動初始化，此服務理論上不會被 FCM 系統呼叫；
 * 以下方法仍加上防呆 try/catch，避免任何非預期狀態導致 App 崩潰 (安全降級、僅記錄錯誤)。
 */
public class AgriFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        try {
            super.onNewToken(token);
            System.out.println("🔥 [FCM] 取得裝置推播 Token");

            // 緩存 Token 至本地 SharedPreferences
            SharedPreferences prefs = getSharedPreferences("AgriDispatchCache", Context.MODE_PRIVATE);
            prefs.edit().putString("fcm_token", token).apply();
        } catch (Exception e) {
            System.err.println("⚠️ [FCM] onNewToken 處理失敗，已安全略過: " + e.getMessage());
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        try {
            super.onMessageReceived(remoteMessage);

            String title = "🌱 智農派工通知";
            String body = "";
            String channel = "dispatch";

            // 1. 若包含 Notification 封包
            if (remoteMessage.getNotification() != null) {
                if (remoteMessage.getNotification().getTitle() != null) {
                    title = remoteMessage.getNotification().getTitle();
                }
                if (remoteMessage.getNotification().getBody() != null) {
                    body = remoteMessage.getNotification().getBody();
                }
            }

            // 2. 若包含 Data 自訂鍵值對
            Map<String, String> data = remoteMessage.getData();
            if (data != null && !data.isEmpty()) {
                if (data.containsKey("title")) {
                    title = data.get("title");
                }
                if (data.containsKey("body") || data.containsKey("message")) {
                    body = data.containsKey("body") ? data.get("body") : data.get("message");
                }
                if (data.containsKey("channel")) {
                    channel = data.get("channel");
                }
            }

            int notificationId = (int) System.currentTimeMillis();
            NotificationHelper.postNotification(this, channel, notificationId, title, body, null);
        } catch (Exception e) {
            System.err.println("⚠️ [FCM] onMessageReceived 處理失敗，已安全略過: " + e.getMessage());
        }
    }
}
