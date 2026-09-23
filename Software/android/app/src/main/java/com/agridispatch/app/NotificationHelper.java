package com.agridispatch.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * 智農派工 (AgriDispatch) - 原生系統通知管理器
 * 支援 Android 8.0+ 渠道管理 (Channels) 與 Android 13+ 通知授權
 */
public class NotificationHelper {

    public static final String CHANNEL_DISPATCH = "channel_dispatch";
    public static final String CHANNEL_WEATHER = "channel_weather";
    public static final String CHANNEL_ATTENDANCE = "channel_attendance";
    public static final String CHANNEL_SYSTEM = "channel_system";

    /**
     * 初始化系統通知渠道 (Android 8.0 / API 26+)
     */
    public static void initChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;

            // 1. 派工與工單通知渠道 (High Importance)
            NotificationChannel dispatchChannel = new NotificationChannel(
                    CHANNEL_DISPATCH,
                    "派工與工單通知",
                    NotificationManager.IMPORTANCE_HIGH
            );
            dispatchChannel.setDescription("派工調度、接駁專車、現場指揮等即時推播");
            dispatchChannel.enableLights(true);
            dispatchChannel.setLightColor(Color.GREEN);
            dispatchChannel.enableVibration(true);
            manager.createNotificationChannel(dispatchChannel);

            // 2. 天候與採收預警渠道 (High Importance)
            NotificationChannel weatherChannel = new NotificationChannel(
                    CHANNEL_WEATHER,
                    "採收預警與天候避險",
                    NotificationManager.IMPORTANCE_HIGH
            );
            weatherChannel.setDescription("突發暴雨冷鏈轉移、產曆採收倒數與防颱搶收");
            weatherChannel.enableLights(true);
            weatherChannel.setLightColor(Color.RED);
            weatherChannel.enableVibration(true);
            manager.createNotificationChannel(weatherChannel);

            // 3. 差勤打卡提醒渠道 (Default Importance)
            NotificationChannel attendanceChannel = new NotificationChannel(
                    CHANNEL_ATTENDANCE,
                    "出勤打卡與工時提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            attendanceChannel.setDescription("GPS 上下班打卡、考勤記錄與現場驗收簽核");
            manager.createNotificationChannel(attendanceChannel);

            // 4. 系統與帳務渠道 (Default Importance)
            NotificationChannel systemChannel = new NotificationChannel(
                    CHANNEL_SYSTEM,
                    "系統公告與帳務通知",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            systemChannel.setDescription("82%合作社分流、18%代管發票與農業部8,800津貼入帳");
            manager.createNotificationChannel(systemChannel);
        }
    }

    /**
     * 發送原生系統通知
     */
    public static void postNotification(Context context, String channelType, int id, String title, String body, Intent targetIntent) {
        try {
            String channelId;
            if ("weather".equalsIgnoreCase(channelType) || "alert".equalsIgnoreCase(channelType)) {
                channelId = CHANNEL_WEATHER;
            } else if ("attendance".equalsIgnoreCase(channelType) || "clockin".equalsIgnoreCase(channelType)) {
                channelId = CHANNEL_ATTENDANCE;
            } else if ("system".equalsIgnoreCase(channelType) || "payment".equalsIgnoreCase(channelType)) {
                channelId = CHANNEL_SYSTEM;
            } else {
                channelId = CHANNEL_DISPATCH;
            }

            Intent intent = targetIntent;
            if (intent == null) {
                intent = new Intent(context, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(context, id, intent, flags);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            NotificationManagerCompat managerCompat = NotificationManagerCompat.from(context);
            // 檢查 Android 13+ 通知授權
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || managerCompat.areNotificationsEnabled()) {
                managerCompat.notify(id, builder.build());
            }
        } catch (SecurityException se) {
            System.err.println("⚠️ 通知權限尚未授權: " + se.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
