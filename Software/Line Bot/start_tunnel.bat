@echo off
chcp 65001 >nul
title 智農派工 - LINE Bot HTTPS 穿透隧道
cd /d "%~dp0"

echo ====================================================================
echo  🌱 智農派工 LINE Bot - HTTPS 穿透隧道
echo  轉發本地: http://localhost:5000 -> 公開 HTTPS 網址
echo ====================================================================
echo.
npx localtunnel --port 5000
pause
