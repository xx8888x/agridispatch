package com.agridispatch.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

/**
 * 智農派工 (AgriDispatch) - Android 原生與 Web 容器交互橋接器 (Pure Java 實現)
 */
public class WebAppInterface {
    private final MainActivity activity;
    private final FusedLocationProviderClient fusedLocationClient;
    private final SharedPreferences sharedPrefs;

    public WebAppInterface(MainActivity activity) {
        this.activity = activity;
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity);
        this.sharedPrefs = activity.getSharedPreferences("AgriDispatchCache", Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public void showToast(String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void vibrate(long milliseconds) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vibratorManager = (VibratorManager) activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vibratorManager != null) {
                    vibratorManager.getDefaultVibrator().vibrate(
                            VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE)
                    );
                }
            } else {
                Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(milliseconds);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @JavascriptInterface
    public void alertHarvest(String cropName, int daysRemaining) {
        vibrate(250);
        String msg = "🚨【" + cropName + "】距離預估採收日尚有 " + daysRemaining + " 天，請儘速鎖定合法外展工班！";
        showToast(msg);
        // 同步發送系統頂部橫幅通知
        sendSystemNotification("🚨 產季採收預警", msg, "weather");
    }

    @JavascriptInterface
    public void sendSystemNotification(String title, String message, String channel) {
        vibrate(150);
        int id = (int) System.currentTimeMillis();
        NotificationHelper.postNotification(activity, channel, id, title, message, null);
    }

    @JavascriptInterface
    public void sendWeatherAlert(String title, String message) {
        vibrate(300);
        int id = (int) System.currentTimeMillis();
        NotificationHelper.postNotification(activity, "weather", id, title, message, null);
        showToast(title + ": " + message);
    }

    @JavascriptInterface
    public void sendDispatchNotification(String title, String message) {
        vibrate(200);
        int id = (int) System.currentTimeMillis();
        NotificationHelper.postNotification(activity, "dispatch", id, title, message, null);
    }

    @JavascriptInterface
    public String getFcmToken() {
        if (!activity.isTrustedPage()) {
            return ""; // 非受信任頁面 (非內建 asset / 非白名單正式網域) 不回傳敏感資料
        }
        try {
            return sharedPrefs.getString("fcm_token", "MOCK_FCM_TOKEN_" + Build.ID);
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public void getFcmTokenAsync(final String callbackFunctionName) {
        JSONObject result = new JSONObject();
        if (!activity.isTrustedPage()) {
            try {
                result.put("success", false);
                result.put("error", "Untrusted page");
            } catch (JSONException ignored) {}
            executeJsCallback(callbackFunctionName, result.toString());
            return;
        }
        String token = getFcmToken();
        try {
            result.put("success", true);
            result.put("token", token);
        } catch (JSONException ignored) {}
        executeJsCallback(callbackFunctionName, result.toString());
    }

    @JavascriptInterface
    public void requestGpsLocation(final String callbackFunctionName) {
        if (!activity.isTrustedPage()) {
            JSONObject result = new JSONObject();
            try {
                result.put("success", false);
                result.put("error", "Untrusted page");
            } catch (JSONException ignored) {}
            executeJsCallback(callbackFunctionName, result.toString());
            return;
        }
        activity.checkLocationPermission(hasPermission -> {
            if (hasPermission) {
                try {
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                            .addOnSuccessListener(location -> {
                                JSONObject result = new JSONObject();
                                try {
                                    if (location != null) {
                                        result.put("success", true);
                                        result.put("latitude", location.getLatitude());
                                        result.put("longitude", location.getLongitude());
                                        result.put("accuracy", location.getAccuracy());
                                        result.put("provider", location.getProvider());
                                        result.put("timestamp", System.currentTimeMillis());
                                    } else {
                                        result.put("success", false);
                                        result.put("error", "Location unavailable, please enable GPS.");
                                    }
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                                executeJsCallback(callbackFunctionName, result.toString());
                            })
                            .addOnFailureListener(e -> {
                                JSONObject result = new JSONObject();
                                try {
                                    result.put("success", false);
                                    result.put("error", e.getMessage() != null ? e.getMessage() : "Failed to get GPS location");
                                } catch (JSONException jsonException) {
                                    jsonException.printStackTrace();
                                }
                                executeJsCallback(callbackFunctionName, result.toString());
                            });
                } catch (SecurityException se) {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("success", false);
                        result.put("error", "Security exception: " + se.getMessage());
                    } catch (JSONException jsonException) {
                        jsonException.printStackTrace();
                    }
                    executeJsCallback(callbackFunctionName, result.toString());
                }
            } else {
                JSONObject result = new JSONObject();
                try {
                    result.put("success", false);
                    result.put("error", "Location permission denied");
                } catch (JSONException jsonException) {
                    jsonException.printStackTrace();
                }
                executeJsCallback(callbackFunctionName, result.toString());
            }
        });
    }

    @JavascriptInterface
    public void saveOfflineData(String key, String value) {
        if (!activity.isTrustedPage()) {
            return; // 非受信任頁面不可寫入本機快取，避免被惡意頁面用來污染快取資料
        }
        sharedPrefs.edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String getOfflineData(String key) {
        if (!activity.isTrustedPage()) {
            return ""; // 非受信任頁面不回傳先前快取的資料 (可能含派工/薪資等業務資料)
        }
        return sharedPrefs.getString(key, "");
    }

    /**
     * 目前 App 的端與身分 (JSON 字串)，例如
     * {"side":"client","role":"WORKER_FOREIGN","nationality":"VN","language":"vi",...}
     */
    @JavascriptInterface
    public String getAppProfile() {
        return activity.getProfile().toJson().toString();
    }

    /**
     * 內建網頁要連的後端 (JSON)：{"apiBase":"…","mode":"demo|production"}，值來自建置設定 (app/build.gradle)。
     * apiBase 為空字串時網頁沿用內建示範資料 (mock)。不含任何機密，所有頁面皆可讀。
     */
    @JavascriptInterface
    public String getBackendConfig() {
        try {
            return new org.json.JSONObject()
                    .put("apiBase", BuildConfig.API_BASE)
                    .put("mode", BuildConfig.APP_MODE)
                    .toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    /** 回到原生身分選擇畫面 (僅客戶端；Server 端無作用)。 */
    @JavascriptInterface
    public void switchRole() {
        activity.runOnUiThread(activity::openRoleSelect);
    }

    @JavascriptInterface
    public String getSystemLanguage() {
        // 外籍移工以選定國別的語言為準 (越南 vi、印尼 id)
        String profileLang = activity.getProfile().language();
        if (!"zh".equals(profileLang)) return profileLang;
        Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        String lang = locale.getLanguage();
        if ("vi".equals(lang)) return "vi";
        if ("in".equals(lang) || "id".equals(lang)) return "id";
        return "zh";
    }

    private void executeJsCallback(String callbackName, String jsonString) {
        activity.runOnUiThread(() -> {
            if (activity.getBindingWebView() != null) {
                activity.getBindingWebView().evaluateJavascript(
                        "window." + callbackName + " && window." + callbackName + "(" + jsonString + ");",
                        null
                );
            }
        });
    }
}
