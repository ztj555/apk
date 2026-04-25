@echo off
cd /d C:\Users\EDY\WorkBuddy\20260422091705\AutoDial\pc-app
taskkill /F /IM AutoDial.exe 2>nul
timeout /t 2 /nobreak >nul
node_modules\.bin\electron debug2.js > debug2-output.log 2>&1
