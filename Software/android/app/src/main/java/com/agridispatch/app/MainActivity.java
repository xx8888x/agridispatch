package com.agridispatch.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.Locale;
import java.util.Map;

/**
 * 智農派工 (AgriDispatch) - 原生 Android 主活動 (Pure Java 實現)
 */
public class MainActivity extends AppCompatActivity {

    private WebView bindingWebView;
    private PermissionCallback permissionCallback;

    /**
     * 目前頁面是否為「受信任頁面」(內建 asset 或白名單正式網域)。
     * 由 WebViewClient 的導覽回呼於 UI 執行緒更新，JS bridge (WebAppInterface) 執行於非 UI 執行緒時讀取此欄位判斷，
     * 避免在非 UI 執行緒呼叫 WebView#getUrl() 造成不確定行為。
     */
    private volatile boolean currentPageTrusted = true;

    public interface PermissionCallback {
        void onResult(boolean granted);
    }

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), (Map<String, Boolean> result) -> {
                boolean fineGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarseGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                boolean isGranted = fineGranted || coarseGranted;
                if (permissionCallback != null) {
                    permissionCallback.onResult(isGranted);
                    permissionCallback = null;
                }
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                // Android 13+ 通知授權結果
            });

    public WebView getBindingWebView() {
        return bindingWebView;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化系統推播通知渠道 (Android 8.0+)
        NotificationHelper.initChannels(this);

        // Android 13+ 主動請求通知權限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // 沉浸式全螢幕
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        bindingWebView = new WebView(this);
        setContentView(bindingWebView);

        configureWebView(bindingWebView);

        // 載入本地離線資產，或回退至伺服器
        bindingWebView.loadUrl("file:///android_asset/www/農業派遣主網站/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // 停用任意檔案系統存取；僅停用 file:// 對一般檔案系統的存取，
        // 不影響內建 file:///android_asset/ 與 file:///android_res/ 資源載入。
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 註冊原生 JavaScriptInterface 橋接器
        webView.addJavascriptInterface(new WebAppInterface(this), "AgriNativeBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                currentPageTrusted = isTrustedUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isTrustedUrl(uri.toString())) {
                    return false; // 交由 WebView 自行載入 (內建 asset 或白名單正式網域)
                }
                // 其餘任意連結一律交給外部系統瀏覽器/App 處理，避免 WebView 被導去任意網域 (open redirect / 釣魚風險)
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    // 裝置上無對應 App 可處理該連結時忽略即可，不中斷使用者體驗
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
    }

    /**
     * 判斷網址是否為受信任來源：內建 asset，或白名單正式網域 (含子網域，需 HTTPS)。
     */
    private boolean isTrustedUrl(String url) {
        if (url == null) {
            return false;
        }
        if (url.startsWith("file:///android_asset/")) {
            return true;
        }
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || host == null) {
                return false;
            }
            String allowedDomain = getString(R.string.allowed_web_domain).toLowerCase(Locale.ROOT);
            String lowerHost = host.toLowerCase(Locale.ROOT);
            return lowerHost.equals(allowedDomain) || lowerHost.endsWith("." + allowedDomain);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 供 WebAppInterface 判斷：目前頁面是否可安全回傳敏感資料 (FCM Token、GPS 座標、離線快取等)。
     */
    public boolean isTrustedPage() {
        return currentPageTrusted;
    }

    public void checkLocationPermission(PermissionCallback callback) {
        boolean fineGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        boolean coarseGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        if (fineGranted || coarseGranted) {
            callback.onResult(true);
        } else {
            this.permissionCallback = callback;
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (bindingWebView != null && bindingWebView.canGoBack()) {
            bindingWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
