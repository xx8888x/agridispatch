@echo off
REM 智農派工 - LINE Bot HTTPS 穿透隧道 (轉呼根目錄的 start_line_tunnel.bat，避免兩份腳本內容不一致)
chcp 65001 >nul
cd /d "%~dp0"
call "%~dp0..\..\start_line_tunnel.bat"
