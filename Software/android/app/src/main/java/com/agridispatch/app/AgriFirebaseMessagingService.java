package com.agridispatch.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/**
 * 智農派工 (AgriDispatch) - FCM (Firebase Cloud Messaging) 雲端推播監聽服務
 * 處理雲端推播 Token 註冊、後台訊息接收與前台橫幅通知呈現
 *
 * Token 只存本機 SharedPreferences（fcm_token）；App 不持有登入憑證，
 * 由網頁 api-client.js 在登入後經 AgriNativeBridge.getFcmToken() 取得並 POST /api/notifications/fcm/register。
 *
 * 注意：若建置時未提供 app/google-services.json (google-services 外掛未套用，見 app/build.gradle)，
 * FirebaseApp 不會被自動初始化，此服務理論上不會被 FCM 系統呼叫；
 * 以下方法仍加上防呆 try/catch，避免任何非預期狀態導致 App 崩潰 (安全降級、僅記錄錯誤)。
 */
public class AgriFirebaseMessagingService extends FirebaseMessagingService {

    static final String PREFS = "AgriDispatchCache";
    static final String KEY_TOKEN = "fcm_token";

    /** App 啟動時主動取一次 token（onNewToken 只在第一次產生或更換時呼叫）；未接上 Firebase 時略過 */
    static void refreshToken(Context context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) return;
            Context app = context.getApplicationContext();
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
                if (token != null && !token.isEmpty()) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply();
                }
            });
        } catch (Exception e) {
            System.err.println("⚠️ [FCM] 取得 token 失敗，已安全略過: " + e.getMessage());
        }
    }

    @Override
    public void onNewToken(String token) {
        try {
            super.onNewToken(token);
            System.out.println("🔥 [FCM] 取得裝置推播 Token");

            // 緩存 Token 至本地 SharedPreferences
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_TOKEN, token).apply();
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
