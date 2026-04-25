@echo off
cd /d C:\Users\EDY\WorkBuddy\20260422091705\AutoDial\pc-app
set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
node_modules\.bin\electron debug.js > debug-output.log 2>&1
echo EXIT CODE: %ERRORLEVEL% >> debug-output.log
