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
 * ?箄噙瘣曉極 (AgriDispatch) - ?? Android 銝餅暑??(Pure Java 撖衣)
 */
public class MainActivity extends AppCompatActivity {

    private WebView bindingWebView;
    private PermissionCallback permissionCallback;

    /**
     * ?桀???臬?箝?靽∩遙????批遣 asset ??甇??蝬脣?)??     * ??WebViewClient ??閬賢??潭 UI ?瑁?蝺?堆?JS bridge (WebAppInterface) ?瑁??潮? UI ?瑁?蝺?霈?迨甈??斗嚗?     * ?踹??券? UI ?瑁?蝺??WebView#getUrl() ??銝Ⅱ摰??箝?     */
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
                // Android 13+ ???蝯?
            });

    public WebView getBindingWebView() {
        return bindingWebView;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ???頂蝯望?剝皜? (Android 8.0+)
        NotificationHelper.initChannels(this);

        // Android 13+ 銝餃?隢??甈?
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // 瘝絡撘?Ｗ?
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        bindingWebView = new WebView(this);
        setContentView(bindingWebView);

        configureWebView(bindingWebView);

        // 頛?砍?Ｙ?鞈嚗???喃撩?
        bindingWebView.loadUrl("file:///android_asset/www/農業派遣主網站/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // ?隞餅?瑼?蝟餌絞摮?嚗?? file:// 撠??祆?獢頂蝯梁?摮?嚗?        // 銝蔣?踹撱?file:///android_asset/ ??file:///android_res/ 鞈?頛??        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 閮餃??? JavaScriptInterface 璈??        webView.addJavascriptInterface(new WebAppInterface(this), "AgriNativeBridge");

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
                    return false; // 鈭斤 WebView ?芾?頛 (?批遣 asset ??甇??蝬脣?)
                }
                // ?園?隞餅????銝敺漱蝯血??函頂蝯梁汗??App ??嚗??WebView 鋡怠??颱遙?雯??(open redirect / ???憸券)
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    // 鋆蔭銝撠? App ?航??府????蕭?亙?荔?銝葉?瑚蝙?刻?撽?                }
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
     * ?斗蝬脣??臬?箏?靽∩遙靘?嚗撱?asset嚗??賢??格迤撘雯??(?怠?蝬脣?嚗? HTTPS)??     */
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
     * 靘?WebAppInterface ?斗嚗???Ｘ?血摰???鞈? (FCM Token?PS 摨扳??蝺翰??)??     */
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

