// debug.js - 调试脚本：启动 Electron 并输出所有渲染进程错误
const { app, BrowserWindow } = require('electron');
const path = require('path');

app.whenReady().then(() => {
  const win = new BrowserWindow({
    width: 420,
    height: 780,
    frame: false,
    backgroundColor: '#111318',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  // 输出所有控制台消息
  win.webContents.on('console-message', (event, level, message, line, sourceId) => {
    const prefix = ['','INFO','WARN','ERROR',''][level] || 'LOG';
    console.log('[' + prefix + ' renderer:' + line + '] ' + message);
  });

  // 输出渲染进程崩溃
  win.webContents.on('render-process-gone', (event, details) => {
    console.error('[CRASH] 渲染进程崩溃:', JSON.stringify(details));
  });

  // 输出未捕获异常
  win.webContents.on('unresponsive', () => {
    console.error('[HANG] 渲染进程无响应');
  });

  // 5秒后自动退出
  setTimeout(() => {
    console.log('\n--- 5秒调试结束，退出 ---');
    app.quit();
  }, 5000);
});
