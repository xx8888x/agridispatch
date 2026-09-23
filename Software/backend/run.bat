@echo off
echo [AgriDispatch] Starting Spring Boot 3 + Hibernate Backend Server...
set MVN_CMD=%~dp0maven\apache-maven-3.9.6\bin\mvn.cmd
if not exist "%MVN_CMD%" (
    mvn -version >nul 2>&1
    if %errorlevel% equ 0 (
        set MVN_CMD=mvn
    ) else (
        echo [AgriDispatch] Error: Maven not found!
        pause
        exit /b 1
    )
)

call "%MVN_CMD%" spring-boot:run
