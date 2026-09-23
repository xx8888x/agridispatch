@echo off
chcp 65001 >nul
title 智農派工 - LINE Bot 外網 HTTPS Webhook 隧道
cd /d "%~dp0"

echo ====================================================================
echo  🌱 智農派工 (AgriDispatch) - LINE Bot 外網 HTTPS 隧道啟動器
echo ====================================================================
echo.
echo  說明：LINE 官方規定 Webhook 必須使用公開的 HTTPS 網址。
echo  本工具透過 npx localtunnel 即刻為您的本機 port 5000 建立加密 HTTPS 穿透！
echo.
echo  [LINE Developers 填寫指引]
echo  1. 前往 LINE Developers Console -> Messaging API
echo  2. 將下方產生的 URL 加上 /callback 填入 Webhook URL：
echo     例如：https://xxxx.loca.lt/callback
echo  3. 開啟 [Use webhook] 開關
echo  4. 點擊 [Verify] 驗證連線是否為 200 OK！
echo ====================================================================
echo.
echo 正在啟動 localtunnel (Port 5000)...
npx localtunnel --port 5000
pause
