@echo off
set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
set ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/
cd /d %~dp0
echo [BUILD] 开始打包 AutoDial PC...
node_modules\.bin\electron-builder --win --x64 --publish never > build-output.log 2>&1
if %errorlevel% == 0 (
    echo [BUILD] 打包成功！
    echo [BUILD] 输出目录: %~dp0dist\
    dir dist\*.exe 2>nul
) else (
    echo [BUILD] 打包失败，查看 build-output.log
    type build-output.log
)
pause
