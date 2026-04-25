// debug2.js - 检查 window.api 是否存在 + 捕获详细错误
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

  win.webContents.on('console-message', (event, level, message, line, sourceId) => {
    console.log('[' + level + ' L' + line + '] ' + message);
  });

  win.webContents.on('did-finish-load', () => {
    // 检查 preload 是否正确注入了 window.api
    win.webContents.executeJavaScript(
      'console.log("typeof window.api = " + typeof window.api);' +
      'console.log("keys of window.api = " + (typeof window.api === "object" ? Object.keys(window.api).join(",") : "N/A"));' +
      'try { console.log("api.invoke type = " + typeof window.api.invoke); } catch(e) { console.log("error accessing api: " + e.message); }'
    );
  });

  setTimeout(() => {
    console.log('--- done ---');
    app.quit();
  }, 5000);
});
