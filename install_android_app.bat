@echo off
chcp 65001 >nul
cd /d "%~dp0Software\android"
call install_apk.bat %*
