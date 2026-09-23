@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo [AgriDispatch LINE Bot] 正在編譯 Java 原始碼...
if not exist "bin" mkdir bin
javac -encoding UTF-8 -d bin src\com\agridispatch\linebot\*.java
if errorlevel 1 (
    echo [AgriDispatch LINE Bot] 編譯失敗！
) else (
    echo [AgriDispatch LINE Bot] 編譯成功！輸出目錄：bin/
)
pause