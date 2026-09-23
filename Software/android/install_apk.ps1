[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "   🌱 智農派工 (AgriDispatch) - Android App 一鍵安裝部署助手" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host ""
$adbCmd = "D:\Android\sdk\platform-tools\adb.exe"
if (-not (Test-Path $adbCmd)) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { $adbCmd = $found.Source } else {
        Write-Host "❌ [錯誤] 找不到 adb.exe，請確認 Android SDK 安裝路徑。" -ForegroundColor Red
        exit 1
    }
}
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$apkPath = Join-Path $scriptDir "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "[1/3] 檢查 APK 檔案..." -ForegroundColor Yellow
if (-not (Test-Path $apkPath)) {
    Write-Host "      APK 尚未建置，正在編譯中..." -ForegroundColor Gray
    & (Join-Path $scriptDir "gradlew.bat") assembleDebug
}
if (-not (Test-Path $apkPath)) {
    Write-Host "❌ [錯誤] APK 建置失敗。" -ForegroundColor Red
    exit 1
}
$apkSizeMB = [math]::Round((Get-Item $apkPath).Length / 1MB, 2)
Write-Host ("      ✅ APK 已就緒：{0} ({1} MB)" -f $apkPath, $apkSizeMB) -ForegroundColor Green
Write-Host ""
Write-Host "[2/3] 偵測已連接之 Android 裝置或模擬器..." -ForegroundColor Yellow
$devices = & $adbCmd devices
Write-Host ($devices -join [Environment]::NewLine)
$deviceLines = ($devices -split "\r?\n") | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of devices" }
if (-not $deviceLines) {
    Write-Host "      ⚠️ 目前尚未偵測到已連線的 Android 裝置。" -ForegroundColor Yellow
    Write-Host "         1. 請以 USB 連接手機，並開啟手機【開發人員選項】中的【USB 偵錯】。"
    Write-Host "         2. 若使用模擬器，請先啟動 Android 模擬器 (AVD / 雷電 / 夜神等)。"
    Write-Host ("         直接傳送 APK 至手機安裝路徑：{0}" -f $apkPath) -ForegroundColor Cyan
} else {
    Write-Host ("      ✅ 偵測到裝置：{0}" -f ($deviceLines -join ", ")) -ForegroundColor Green
    Write-Host ""
    Write-Host "[3/3] 正在安裝 APK 至裝置 (保留既有資料覆蓋安裝 -r)..." -ForegroundColor Yellow
    & $adbCmd install -r -d $apkPath
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "======================================================================" -ForegroundColor Green
        Write-Host "   🎉 安裝完成！正在手機上自動啟動 智農派工 App..." -ForegroundColor Green
        Write-Host "======================================================================" -ForegroundColor Green
        & $adbCmd shell am start -n com.agridispatch.app/.MainActivity
    } else {
        Write-Host "❌ [警告] 安裝未成功，請確認手機螢幕是否跳出「允許 USB 偵錯」授權提示。" -ForegroundColor Red
    }
}
Write-Host ""