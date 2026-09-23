# 智農派工 (AgriDispatch) - Android 原生殼 (Pure Java WebView 容器)

本專案為「智農派工」網頁前端的 Android 原生外殼，以 `WebView` 載入 `app/src/main/assets/www/`（前端頁面，由其他代理維護，本目錄不可異動該資料夾）內容，並提供 GPS 定位、系統通知、震動、FCM 雲端推播等原生能力，透過 `AgriNativeBridge` JavaScript Interface 橋接給前端呼叫。

---

## ⚠️ 建置環境限制（本次修改未實際編譯驗證）

本次修改是在**沒有 Android SDK / 無網路下載套件**的環境下完成，僅能：
- 人工檢查 Java 原始碼的 import、型別與呼叫是否正確
- 確認 Gradle 檔案（`build.gradle`、`settings.gradle`、`gradle-wrapper.properties`）語法正確

**尚未實際跑過 `gradle build` 或 `gradle assembleDebug`**，請在您本機（有 Android Studio / 網路）的環境下建置一次以確認無誤。

---

## 🧩 Gradle Wrapper 缺少 gradlew / gradlew.bat / gradle-wrapper.jar

本專案目錄目前只有 `gradle/wrapper/gradle-wrapper.properties`，缺少 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`（依規定不可在此環境下載檔案，故未補上）。請擇一方式補齊：

1. **用 Android Studio 開啟本目錄**（`Software/android`）：Android Studio 偵測到專案後會自動產生缺少的 wrapper 檔案。
2. **或已安裝 Gradle 的情況下**，在本目錄執行：
   ```bash
   gradle wrapper --gradle-version 8.7
   ```
   會依 `gradle-wrapper.properties` 內的版本設定補齊 `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`。

---

## 🏗️ 建置 APK 步驟

1. 用 Android Studio 開啟 `Software/android` 目錄（或依上一節補齊 gradle wrapper 後用終端機）。
2. 視需要設定 FCM（見下方「FCM 推播設定」）與正式網域（見 `app/src/main/res/values/strings.xml` 的 `allowed_web_domain`，預設 `agridispatch.tw`；`MainActivity` 的 WebView 只允許載入內建 assets 與此白名單網域）。
3. Debug 建置：
   ```bash
   ./gradlew assembleDebug
   ```
   或直接在 Android Studio 點擊 Run。
4. Release 建置（需先完成「Release 簽章設定」）：
   ```bash
   ./gradlew assembleRelease
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
3. 重新執行 `./gradlew assembleRelease`，輸出的 APK 即為已簽章版本。

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
