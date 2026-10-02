@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo [AgriDispatch LINE Bot] 啟動本地離線測試模擬器...

rem 每次都重新編譯，避免 bin\ 殘留舊版 class（例如拆成雙帳號前的版本）
echo [AgriDispatch LINE Bot] 正在編譯 Java 原始碼...
if not exist "bin" mkdir bin
javac -encoding UTF-8 -d bin src\com\agridispatch\linebot\*.java
if errorlevel 1 (
    echo [AgriDispatch LINE Bot] 編譯失敗！
    pause
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp bin com.agridispatch.linebot.TestSimulator
pause