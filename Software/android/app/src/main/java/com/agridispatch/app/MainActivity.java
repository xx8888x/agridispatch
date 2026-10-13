package com.agridispatch.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;
import com.agridispatch.app.profile.AppSide;
import com.agridispatch.app.profile.ProfileStore;
import com.agridispatch.app.profile.UserProfile;
import java.util.Locale;
import java.util.Map;

/**
 * 智農派工 (AgriDispatch) - 原生 Android 主活動 (Pure Java 實現)
 * Server 端 (server flavor) 直接載入科技後台；客戶端 (client flavor) 依 ProfileStore 的身分載入對應登入頁，
 * 尚未選身分時轉到 RoleSelectActivity。
 */
public class MainActivity extends AppCompatActivity {

    /**
     * 內建網頁改由 WebViewAssetLoader 以 https://appassets.androidplatform.net/www/ 提供 (規格 06 第陸章)：
     * file:///android_asset/ 的網頁來源 (origin) 是 "null"，後端 CORS 一定擋，App 只能看離線示範資料；
     * 換成固定的 https 來源後，後端白名單 (agri.cors.allowed-origins) 加入此來源即可呼叫 /api。
     */
    static final String ASSET_HOST = "appassets.androidplatform.net";
    static final String ASSET_WWW = "https://" + ASSET_HOST + "/www/";
    private static final String ASSET_WWW_PATH = "/www/";

    private WebViewAssetLoader assetLoader;

    private WebView bindingWebView;
    private UserProfile profile;
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

        profile = ProfileStore.load(this);
        if (profile == null) {
            // 客戶端尚未選擇身分 (農場主 / 本勞 / 外勞)
            openRoleSelect();
            return;
        }

        // 初始化系統推播通知渠道 (Android 8.0+)
        NotificationHelper.initChannels(this);
        // FCM：有 google-services.json 時取得推播 token 存本機，由網頁登入後上傳後端 (api-client.js)
        AgriFirebaseMessagingService.refreshToken(this);

        // Android 13+ 主動請求通知權限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // 滿版顯示，WebView 外包一層深色容器並依系統列 insets 內縮 (見 SystemBarInsets)
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(ContextCompat.getColor(this, R.color.ad_bg));
        bindingWebView = new WebView(this);
        container.addView(bindingWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(container);
        SystemBarInsets.apply(getWindow(), container);

        // debug 版開放 chrome://inspect 偵錯 WebView；release 版關閉
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        // 內建 assets/www 以 https://appassets.androidplatform.net/www/ 提供 (見 ASSET_WWW 說明)
        // PathHandler 收到的是去掉 "/www/" 後的路徑，要補回 "www/" 才是 assets 內的位置
        // （直接掛 AssetsPathHandler 會去開 assets/index.html，找不到時回空回應 → ERR_INVALID_RESPONSE）
        WebViewAssetLoader.AssetsPathHandler assets = new WebViewAssetLoader.AssetsPathHandler(this);
        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(ASSET_HOST)
                .addPathHandler(ASSET_WWW_PATH, path -> assets.handle("www/" + path))
                .build();

        configureWebView(bindingWebView);

        // 依端與身分載入本地離線資產的啟動頁
        bindingWebView.loadUrl(ASSET_WWW + profile.entryPage());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // 停用 file:// 存取：內建網頁已改走 WebViewAssetLoader (https)，不再需要 file:///android_asset/
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
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // appassets.androidplatform.net/www/** → assets/www/**；其他網址 (後端 API、正式網域) 照常走網路
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                currentPageTrusted = isTrustedUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (isTrustedUrl(url)) {
                    injectProfile(view, url);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (interceptForSide(uri)) {
                    return true;
                }
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
     * 判斷網址是否為受信任來源：內建 asset (appassets.androidplatform.net/www/)，或白名單正式網域 (含子網域，需 HTTPS)。
     */
    private boolean isTrustedUrl(String url) {
        if (url == null) {
            return false;
        }
        if (url.startsWith(ASSET_WWW)) {
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
     * 依端攔截站內導覽 (僅內建 asset)：
     * 客戶端不打包科技後台，點到後台連結改提示；點到網頁的「切換角色」(login.html) 改回原生身分選擇畫面。
     */
    private boolean interceptForSide(Uri uri) {
        if (!ASSET_HOST.equals(uri.getHost()) || profile.side != AppSide.CLIENT) {
            return false;
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith(ASSET_WWW_PATH)) {
            return false;
        }
        String page = path.substring(ASSET_WWW_PATH.length());
        if (page.startsWith("TechBackend/")) {
            Toast.makeText(this, R.string.admin_only_in_server_app, Toast.LENGTH_LONG).show();
            return true;
        }
        if (page.equals("AgriDispatch/login.html")) {
            openRoleSelect();
            return true;
        }
        return false;
    }

    /**
     * 頁面載入完成後注入身分：window.AGRI_APP_PROFILE + 'agri:app-profile' 事件。
     * 外籍移工工單頁 (worker-foreign.html) 另依國別切換介面語言 (該頁只支援 zh / vi / id)。
     */
    private void injectProfile(WebView view, String url) {
        String json = profile.toJson().toString();
        StringBuilder js = new StringBuilder()
                .append("(function(){var p=").append(json).append(";")
                .append("window.AGRI_APP_PROFILE=p;")
                .append("window.dispatchEvent(new CustomEvent('agri:app-profile',{detail:p}));");
        String lang = profile.language();
        if (url.contains("worker-foreign.html") && ("vi".equals(lang) || "id".equals(lang))) {
            js.append("if(typeof window.setLang==='function'){window.setLang('").append(lang).append("');}");
        }
        js.append("})();");
        view.evaluateJavascript(js.toString(), null);
    }

    public UserProfile getProfile() {
        return profile;
    }

    /** 回到原生身分選擇畫面 (僅客戶端)。 */
    public void openRoleSelect() {
        if (AppSide.current() != AppSide.CLIENT) {
            return;
        }
        Intent intent = new Intent(this, RoleSelectActivity.class);
        intent.putExtra(RoleSelectActivity.EXTRA_CHOOSE, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
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
