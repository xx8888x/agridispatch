# 智農派工 (AgriDispatch) - Android 原生殼 (Pure Java WebView 容器)

本專案為「智農派工」網頁前端的 Android 原生外殼，以 `WebView` 載入 `app/src/main/assets/www/`（前端頁面，由其他代理維護，本目錄不可異動該資料夾）內容，並提供 GPS 定位、系統通知、震動、FCM 雲端推播等原生能力，透過 `AgriNativeBridge` JavaScript Interface 橋接給前端呼叫。

---

## 🧭 雙端架構：Server 端 / 客戶端（2026-10-02）

同一份程式碼以 Gradle product flavor（dimension `side`）產出兩支 APK，分類依據見 [`docs/角色與人力分類表.md`](docs/角色與人力分類表.md)，規格見 `07_系統分析及文件/06_Android雙端App規格書.md`。

| flavor | App 名稱 | applicationId | 啟動 | 前端資料夾 | 發佈 |
|---|---|---|---|---|---|
| `client` | 智農派工 | `com.agridispatch.app` | `RoleSelectActivity`：農場主 / 本勞（類型）/ 外勞（國別）→ 對應登入頁 | 排除 `科技後台/` | Google Play 公開上架 |
| `server` | 智農派工後台 | `com.agridispatch.app.admin` | `MainActivity` → `科技後台/admin-login.html` | 全部 | 僅 Play 內部測試軌，不公開上架 |

建置指令：`assembleClientDebug`、`assembleServerDebug`（或 `assembleDebug` 一次建兩支）；輸出在 `app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk`。
安裝：`install_apk.bat`（客戶端，預設）或 `install_apk.bat -Flavor server`。

> FCM：兩支 App 套件名稱不同，`google-services.json` 需在 Firebase 同時註冊 `com.agridispatch.app` 與 `com.agridispatch.app.admin`，否則 google-services 外掛會建置失敗。

---

## ✅ 建置驗證狀態（2026-09-23）

已在本機實際建置成功：`assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`（約 7.3 MB）。
尚未在實機或模擬器上安裝執行。

本機建置環境（免安裝版，皆位於 `D:\Android\`，不修改系統環境變數）：

| 項目 | 位置 / 版本 |
|---|---|
| JDK | `D:\Android\jdk17`（Temurin 17；本機預設的 JDK 26 太新，Gradle 8.7 無法執行） |
| Android SDK | `D:\Android\sdk`（platform-tools、platforms;android-35、build-tools;35.0.0） |
| Gradle | 專案內 `gradlew`（8.7），或 `D:\Android\gradle-8.7` |
| SDK 路徑 | `local.properties`：`sdk.dir=D:/Android/sdk`（用正斜線；此檔已 gitignore） |

---

## ⚠️ 專案路徑含中文與括號：必須對應英文磁碟代號才能建置

專案位於 `D:\派遣專案網站(農事及其它)\`，會造成兩個問題：
1. Android Gradle Plugin 拒絕在含非 ASCII 字元的路徑下建置（`Your project path contains non-ASCII characters`）。
2. `gradlew.bat` 在含括號 `(` `)` 的路徑下會被 cmd 誤判語法而無法啟動。

解法：用 `subst` 暫時把本目錄對應成英文磁碟代號（只在本次登入有效，不改動任何檔案），建完再移除：

```bat
subst Z: "D:\派遣專案網站(農事及其它)\Software\android"
set JAVA_HOME=D:\Android\jdk17
Z:
gradlew.bat assembleClientDebug assembleServerDebug
subst Z: /D
```

或以 Android Studio 開啟 `Z:\`（同樣先 subst）。長期建議把專案搬到純英文路徑（例如 `D:\AgriDispatch\`）。

---

## 🏗️ 建置 APK 步驟

1. 依上一節以 `subst` 對應英文磁碟代號（或用 Android Studio 開啟對應後的目錄）。
2. 視需要設定 FCM（見下方「FCM 推播設定」）與正式網域（見 `app/src/main/res/values/strings.xml` 的 `allowed_web_domain`，預設 `agridispatch.tw`；`MainActivity` 的 WebView 只允許載入內建 assets 與此白名單網域）。
3. Debug 建置：
   ```bat
   gradlew.bat assembleDebug
   ```
   或直接在 Android Studio 點擊 Run。前端頁面來自 `app/src/main/assets/www/`，由 `Software/tools/sync-frontend.js` 從 `Software/demo/` 同步產生，建置前請先執行同步。
4. Release 建置（需先完成「Release 簽章設定」）：
   ```bat
   gradlew.bat assembleRelease
   ```
   輸出於 `app/build/outputs/apk/release/`。

---

## 🔥 FCM 推播設定（選用）

`app/build.gradle` 已改為**條件式套用** `google-services` 外掛：只有偵測到 `app/google-services.json` 存在時才會套用；不存在時建置仍會成功，僅 FCM 推播無法實際運作（`AgriFirebaseMessagingService` 已加上防呆機制，不會造成崩潰）。

設定步驟：
1. 前往 [Firebase Console](https://console.firebase.google.com/)，建立或選擇專案。
2. 新增 Android App，套件名稱填入 `com.agridispatch.app`。
3. 下載 `google-services.json`，放到 `app/google-services.json`（此路徑已列於根目錄 `.gitignore`，不會被提交）。
   - 可參考 `app/google-services.json.example` 了解欄位結構（該檔為佔位範本，非真實金鑰，請勿直接使用）。
4. 重新建置專案即可啟用 FCM。

---

## 🔑 Release 簽章設定

`app/build.gradle` 會從（本目錄下的）`keystore.properties` 讀取簽章設定；該檔已列於 `.gitignore`，不會被提交。**檔案不存在時，release 建置不會設定 signingConfig**，只會產出未簽章的 APK。

設定步驟：
1. 若尚未有正式簽章金鑰，先產生一份（**請妥善備份**，遺失將無法再更新已上架的 App）：
   ```bash
   keytool -genkeypair -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
   ```
2. 複製 `keystore.properties.example` 為 `keystore.properties`，填入實際的 `storeFile` / `storePassword` / `keyAlias` / `keyPassword`。
3. 重新執行 `gradlew.bat assembleRelease`（同樣需先 subst），輸出的 APK 即為已簽章版本。

---

## 📱 SDK 版本與上架注意事項

- `compileSdk` / `targetSdk` 已提升至 **35**（配合 Google Play 自 2025/8 起「新上架 App 須 targetSdk 35 以上」的規定）。
- 對應提升 Android Gradle Plugin 至 **8.6.1**（支援 compileSdk 35 的最低建議版本），Gradle Wrapper 版本同步調整為 **8.7**（AGP 8.6.x 相容的 Gradle 版本）。
- **請在上架前務必至 [Play Console 官方公告](https://support.google.com/googleplay/android-developer/answer/11926878) 確認當下最新的 targetSdk 規定**——2026 年之後可能已進一步要求 targetSdk 36 以上，本次調整僅反映撰寫當下（2026 上半年）已知的規定，不代表未來仍然適用。
- 目前 `minSdk` 維持 24，未變動。

---

## 🔒 本次一併強化的安全設定（供參考）

- `MainActivity`：WebView 僅允許載入內建 assets (`file:///android_asset/`) 與白名單正式網域（見 `strings.xml` 的 `allowed_web_domain`），其餘連結一律交由系統瀏覽器開啟；`AgriNativeBridge` 中會回傳敏感資料的方法（FCM Token、GPS 座標、離線快取）僅在受信任頁面才會回應。
- `AndroidManifest.xml`：移除全域明文流量開放，改用 `res/xml/network_security_config.xml`，僅開放開發用位址（`10.0.2.2` / `localhost` / `127.0.0.1`）明文 HTTP，正式網域一律強制 HTTPS。
