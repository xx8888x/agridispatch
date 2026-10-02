@echo off
chcp 65001 >nul
echo ======================================================================
echo   🌱 智農派工 (AgriDispatch) 雙核心伺服器一鍵啟動與狀態守護
echo ======================================================================
echo.

set ROOT_DIR=%~dp0
cd /d "%ROOT_DIR%"

rem LINE Bot 與後端互相呼叫內部端點的共用金鑰（07_系統分析及文件/05_LINE官方帳號拆分規格.md 6.3）
rem 未設定時每次啟動產生一組，由下面兩個視窗共用；兩邊金鑰不同時 LINE Bot 會改用記憶體暫存身分
if not defined AGRI_LINEBOT_INTERNAL_KEY (
    for /f %%k in ('powershell -NoProfile -Command "[guid]::NewGuid().Guid"') do set "AGRI_LINEBOT_INTERNAL_KEY=%%k"
)

rem 後端位置：單體 Software\backend；若為拆分版（子專案）則改用 農業派遣主網站
set BACKEND_DIR=%ROOT_DIR%Software\backend
if not exist "%BACKEND_DIR%\compile.bat" set BACKEND_DIR=%ROOT_DIR%Software\backend\農業派遣主網站

echo [1/2] 檢查後端伺服器 (Port: 3000)...
netstat -ano | findstr ":3000 " | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo       ✅ 後端 Spring Boot 伺服器已在運行中 (Port: 3000)
) else (
    echo       🚀 正在啟動後端 Spring Boot 伺服器（demo 路演模式）...
    rem 正式上線改為 call run.bat（production，需設定 AGRI_TOKEN_SECRET、AGRI_ADMIN_PASSWORD）
    start "AgriDispatch-Backend-3000" /D "%BACKEND_DIR%" cmd /k "call compile.bat && call run.bat demo"
    ping 127.0.0.1 -n 6 >nul
)

echo.
echo [2/2] 檢查 LINE Bot 伺服器 (Port: 5000)...
netstat -ano | findstr ":5000 " | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo       ✅ LINE Bot 服務引擎已在運行中 (Port: 5000)
    echo       ⚠️ 若後端是本次才啟動，兩者內部金鑰不同；請關閉 LINE Bot 視窗後重新執行本檔
) else (
    echo       🚀 正在啟動 LINE Bot 服務引擎...
    start "AgriDispatch-LineBot-5000" /D "%ROOT_DIR%Software\Line Bot" cmd /k "call run.bat"
    ping 127.0.0.1 -n 3 >nul
)

echo.
echo ======================================================================
echo   🎉 系統全服務已就緒！可直接透過瀏覽器訪問以下入口：
echo.
echo   🌐 智農派工 首頁入口:       http://localhost:3000/demo/農業派遣主網站/index.html
echo   🌾 小農派工與金流收銀台:     http://localhost:3000/demo/農業派遣主網站/farmer.html
echo   👥 角色分流登入門戶:         http://localhost:3000/demo/農業派遣主網站/login.html
echo   💻 科技營運管理總台:         http://localhost:3000/demo/科技後台/admin.html
echo   📱 LINE Bot 實機體驗館:      http://localhost:3000/demo/linebot/line.html
echo.
echo   🔗 如需 LINE 官方實機 Webhook 穿透，請雙擊運行：start_line_tunnel.bat
echo ======================================================================
echo.
pause
