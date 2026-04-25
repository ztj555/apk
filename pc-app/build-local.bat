@echo off
cd /d C:\Users\EDY\WorkBuddy\20260422091705\AutoDial\pc-app
taskkill /F /IM AutoDial.exe 2>nul
timeout /t 2 /nobreak >nul
set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
node pack.js > pack-output.log 2>&1
echo EXIT: %ERRORLEVEL% >> pack-output.log
