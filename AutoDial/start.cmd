@echo off
title AutoDial

:: ==================== Config ====================

:: ==================== Config End ====================

cd /d "%~dp0"

echo.
echo   AutoDial Starter
echo.

:: ---- Check Node.js ----
where node >nul 2>nul
if %errorlevel% neq 0 (
    echo  [ERROR] Node.js not found
    echo  Download: https://nodejs.org/
    pause
    exit /b 1
)

:: ---- Install deps if missing ----
if not exist "pc-app\node_modules\electron\dist\electron.exe" (
    echo  [INFO] Installing dependencies...
    cd /d "%~dp0pc-app"
    call npm install
    if %errorlevel% neq 0 (
        echo  [ERROR] npm install failed
        pause
        exit /b 1
    )
    cd /d "%~dp0"
)


:: ---- Start PC App ----
echo  [START] AutoDial PC...
cd /d "%~dp0pc-app"
node node_modules\electron\cli.js .
