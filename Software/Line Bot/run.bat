@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo [AgriDispatch LINE Bot] 啟動中...

rem 每次啟動都重新編譯，避免 bin\ 殘留舊版 class 導致跑到舊程式
echo [AgriDispatch LINE Bot] 編譯中...
if not exist "bin" mkdir bin
javac -encoding UTF-8 -d bin src\com\agridispatch\linebot\*.java
if errorlevel 1 (
    echo [AgriDispatch LINE Bot] 編譯失敗，請檢查錯誤訊息。
    pause
    exit /b 1
)

echo [AgriDispatch LINE Bot] 啟動伺服器 (Port: 5000)...
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp bin com.agridispatch.linebot.LineBotServer
pause