@echo off
chcp 65001 >nul
echo ======================================================================
echo   🌱 智農派工 (AgriDispatch) 雙核心伺服器一鍵啟動與狀態守護
echo ======================================================================
echo.

set ROOT_DIR=%~dp0
cd /d "%ROOT_DIR%"

echo [1/2] 檢查後端伺服器 (Port: 3000)...
netstat -ano | findstr ":3000 " | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo       ✅ 後端 Spring Boot 伺服器已在運行中 (Port: 3000)
) else (
    echo       🚀 正在啟動後端 Spring Boot 伺服器...
    start "AgriDispatch-Backend-3000" cmd /k "cd /d ""%ROOT_DIR%Software\backend"" && call compile.bat && call run.bat"
    timeout /t 5 >nul
)

echo.
echo [2/2] 檢查 LINE Bot 伺服器 (Port: 5000)...
netstat -ano | findstr ":5000 " | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo       ✅ LINE Bot 服務引擎已在運行中 (Port: 5000)
) else (
    echo       🚀 正在啟動 LINE Bot 服務引擎...
    start "AgriDispatch-LineBot-5000" cmd /k "cd /d ""%ROOT_DIR%Software\Line Bot"" && call run.bat"
    timeout /t 2 >nul
)

echo.
echo ======================================================================
echo   🎉 系統全服務已就緒！可直接透過瀏覽器訪問以下入口：
echo.
echo   🌐 智農派工 首頁入口:       http://localhost:3000/demo/index.html
echo   🌾 小農派工與金流收銀台:     http://localhost:3000/demo/farmer.html
echo   👥 角色分流登入門戶:         http://localhost:3000/demo/login.html
echo   💻 科技營運管理總台:         http://localhost:3000/demo/admin.html
echo   📱 LINE Bot 實機體驗館:      http://localhost:3000/demo/line.html
echo   🎯 投資人路演展示頁:         http://localhost:3000/demo/showcase.html
echo.
echo   🔗 如需 LINE 官方實機 Webhook 穿透，請雙擊運行：start_line_tunnel.bat
echo ======================================================================
echo.
pause
