'use strict';

const { app, BrowserWindow, ipcMain, screen, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const http = require('http');
const WebSocket = require('ws');
const dgram = require('dgram');
const os = require('os');
const fs = require('fs');
const { exec, execSync } = require('child_process');
const crypto = require('crypto');

// ==================== 日志系统 ====================
const _logBuffer = [];

function _pushLog(level, text) {
  const entry = { level, text, ts: Date.now() };
  _logBuffer.push(entry);
  if (_logBuffer.length > 200) _logBuffer.shift();
  // 广播给所有渲染进程
  [mainWindow, floatBarWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      try { win.webContents.send('server-log', entry); } catch (e) {}
    }
  });
}

function _flushLogBuffer(win) {
  _logBuffer.forEach(entry => {
    try { win.webContents.send('server-log', entry); } catch (e) {}
  });
}

const _origLog = console.log.bind(console);
const _origError = console.error.bind(console);
const _origWarn = console.warn.bind(console);
console.log = (...args) => { const t = args.join(' '); _origLog(t); _pushLog('info', t); };
console.error = (...args) => { const t = args.join(' '); _origError(t); _pushLog('error', t); };
console.warn = (...args) => { const t = args.join(' '); _origWarn(t); _pushLog('warn', t); };

// ==================== 常量与工具函数 ====================
function getMacAddress() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (!iface.internal && iface.mac && iface.mac !== '00:00:00:00:00:00') {
        return iface.mac;
      }
    }
  }
  return os.hostname();
}

function generatePinCode() {
  const mac = getMacAddress();
  const hash = crypto.createHash('sha256').update(mac).digest('hex');
  const num = parseInt(hash.substring(0, 8), 16);
  return String(num % 9000 + 1000);
}

function getLocalIP() {
  const interfaces = os.networkInterfaces();
  const candidates = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (!iface.internal && iface.family === 'IPv4') {
        candidates.push({ name, address: iface.address });
      }
    }
  }
  const preferred = candidates.find(c => c.address.startsWith('192.168') || c.address.startsWith('10.') || c.address.startsWith('172.'));
  return preferred ? preferred.address : (candidates[0] ? candidates[0].address : '127.0.0.1');
}

function getSubnet() {
  const ip = getLocalIP();
  const parts = ip.split('.');
  return parts[0] + '.' + parts[1] + '.' + parts[2] + '.';
}

const PIN_CODE = generatePinCode();
const LOCAL_IP = getLocalIP();
const SUBNET = getSubnet();
const PORT = 35432;
const DISCOVERY_PORT = 35433;

let phoneSocket = null;
let mainWindow = null;
let floatBarWindow = null;

// ==================== 窗口创建 ====================
function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 420,
    height: 780,
    minWidth: 380,
    minHeight: 600,
    frame: false,           // 无边框
    transparent: false,
    backgroundColor: '#111318',
    resizable: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  mainWindow.setMenuBarVisibility(false);

  mainWindow.on('closed', () => {
    mainWindow = null;
    if (floatBarWindow && !floatBarWindow.isDestroyed()) {
      floatBarWindow.close();
    }
    app.quit();
  });

  // 补发历史日志 + 主动推送 info
  mainWindow.webContents.on('did-finish-load', () => {
    _flushLogBuffer(mainWindow);
    // 主动推送 IP 和配对码（避免 invoke 时序问题）
    try {
      mainWindow.webContents.send('info-push', {
        ip: LOCAL_IP,
        pin: PIN_CODE,
        port: PORT
      });
      // 如果手机已连接，补发状态
      if (phoneSocket && phoneSocket.readyState === WebSocket.OPEN) {
        mainWindow.webContents.send('status-update', { connected: true, phoneIP: null });
      }
    } catch (e) {}
  });
}

function createFloatBarWindow() {
  const primaryDisplay = screen.getPrimaryDisplay();
  const { width: screenW, height: screenH } = primaryDisplay.workAreaSize;

  floatBarWindow = new BrowserWindow({
    width: 340,
    height: 48,
    x: screenW - 360,
    y: screenH - 80,
    frame: false,
    transparent: true,
    resizable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    focusable: true,   // 必须为 true，否则鼠标事件无法触发（拖拽失效）
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  floatBarWindow.loadFile(path.join(__dirname, 'renderer', 'floatbar.html'));
  floatBarWindow.setIgnoreMouseEvents(false);
  floatBarWindow.setVisibleOnAllWorkspaces(true);

  floatBarWindow.on('closed', () => {
    floatBarWindow = null;
  });

  floatBarWindow.webContents.on('did-finish-load', () => {
    _flushLogBuffer(floatBarWindow);
  });
}

// ==================== IPC 处理 ====================

// 获取服务器信息
ipcMain.handle('get-info', async () => {
  return {
    pin: PIN_CODE,
    ip: LOCAL_IP,
    port: PORT,
    connected: phoneSocket !== null,
    hostname: os.hostname(),
    firewall: firewallWarning ? 'warning' : 'ok'
  };
});

// 读取系统剪贴板
ipcMain.handle('read-clipboard', async () => {
  try {
    let text = '';
    if (process.platform === 'win32') {
      text = execSync('powershell -command "Get-Clipboard"', { timeout: 1000, encoding: 'utf8' }).trim();
    } else if (process.platform === 'darwin') {
      text = execSync('pbpaste', { timeout: 1000, encoding: 'utf8' }).trim();
    } else {
      text = execSync('xclip -selection clipboard -o 2>/dev/null || xdotool type --clearmodifiers --file -', { timeout: 1000, encoding: 'utf8' }).trim();
    }
    return { text };
  } catch (e) {
    return { text: '' };
  }
});

// OCR 截屏扫描
ipcMain.handle('scan-screen', async (event, x, y, w, h) => {
  const psScript = `
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
[Windows.Media.Ocr.OcrEngine, Windows.Media.Ocr, ContentType = WindowsRuntime] | Out-Null
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null

Add-Type -AssemblyName System.Windows.Forms
$screen = [System.Windows.Forms.Screen]::PrimaryScreen
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.CopyFromScreen($x, $y, 0, 0, (New-Object System.Drawing.Size($w, $h)))

$tmpPath = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), 'autodial_ocr.bmp')
$bmp.Save($tmpPath, [System.Drawing.Imaging.ImageFormat]::Bmp)
$gfx.Dispose()
$bmp.Dispose()

$file = [Windows.Storage.StorageFile]::GetFileFromPathAsync($tmpPath).AsTask().GetAwaiter().GetResult()
$stream = $file.OpenAsync([Windows.Storage.FileAccessMode]::Read).AsTask().GetAwaiter().GetResult()
$decoder = [Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream).AsTask().GetAwaiter().GetResult()
$softwareBitmap = $decoder.GetSoftwareBitmapAsync([Windows.Graphics.Imaging.BitmapPixelFormat]::Bgra8, [Windows.Graphics.Imaging.BitmapAlphaMode]::Premultiplied).AsTask().GetAwaiter().GetResult()
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('zh-Hans-CN'))
if ($null -eq $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
$result = $engine.RecognizeAsync($softwareBitmap).AsTask().GetAwaiter().GetResult()
$text = $result.Text
$stream.Dispose()
Remove-Item $tmpPath -Force -ErrorAction SilentlyContinue
Write-Output $text
`;

  try {
    const ocrText = execSync(
      'powershell -NoProfile -Command "' + psScript.replace(/"/g, '\\"').replace(/\n/g, ' ') + '"',
      { timeout: 10000, encoding: 'utf8', maxBuffer: 1024 * 1024 }
    ).trim();

    const phoneMatch = ocrText.match(/1[3-9]\d{9}/g);
    const phone = phoneMatch ? phoneMatch[0] : null;

    // 找到手机号写入剪贴板
    if (phone && process.platform === 'win32') {
      try {
        execSync('powershell -command "Set-Clipboard -Value \'' + phone + '\'"', { timeout: 1000 });
      } catch (e) {}
    }

    console.log('[OCR] 识别结果: ' + (phone ? '发现号码 ' + phone : '未发现手机号'));
    return { text: ocrText, phone };
  } catch (e) {
    console.error('[OCR] 识别失败:', e.message);
    return { text: '', phone: null, error: e.message };
  }
});

// 拨号指令
ipcMain.on('dial', (event, number) => {
  if (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN) {
    _sendError(event, '手机未连接');
    return;
  }
  phoneSocket.send(JSON.stringify({ type: 'dial', number }));
  console.log('[拨号] ' + number);
  // 通知 UI 拨号已发送
  [mainWindow, floatBarWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      try { win.webContents.send('dial-sent', { number }); } catch (e) {}
    }
  });
});

// 挂断指令
ipcMain.on('hangup', (event) => {
  if (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN) {
    _sendError(event, '手机未连接');
    return;
  }
  phoneSocket.send(JSON.stringify({ type: 'hangup' }));
  console.log('[挂断] 电脑端发送挂断指令');
  [mainWindow, floatBarWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      try { win.webContents.send('hangup-sent'); } catch (e) {}
    }
  });
});

// 窗口置顶
ipcMain.on('set-topmost', (event, enable) => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.setAlwaysOnTop(enable);
  }
  console.log('[置顶] ' + (enable ? '已开启' : '已关闭'));
});

// 窗口控制（最小化/关闭）
ipcMain.on('window-control', (event, action) => {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (action === 'minimize') mainWindow.minimize();
  if (action === 'close') mainWindow.close();
});

// 悬浮横条位置反馈
ipcMain.on('floatbar-position', (event, rect) => {
  // 可用于保存位置，后续扩展
});

// 悬浮横条拖拽移动
ipcMain.on('floatbar-move', (event, pos) => {
  if (floatBarWindow && !floatBarWindow.isDestroyed()) {
    floatBarWindow.setPosition(Math.round(pos.x), Math.round(pos.y));
  }
});

// 悬浮横条请求获取位置
ipcMain.on('floatbar-get-position', (event) => {
  if (floatBarWindow && !floatBarWindow.isDestroyed()) {
    const bounds = floatBarWindow.getBounds();
    try { event.sender.send('floatbar-position-reply', { x: bounds.x, y: bounds.y }); } catch (e) {}
  }
});

function _sendError(event, message) {
  const win = BrowserWindow.fromWebContents(event.sender);
  if (win && !win.isDestroyed()) {
    try { win.webContents.send('error', { message }); } catch (e) {}
  }
}

// ==================== WebSocket 服务器 ====================
const server = http.createServer((req, res) => {
  // 兼容手机端可能请求页面
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({
    pin: PIN_CODE,
    ip: LOCAL_IP,
    port: PORT,
    connected: phoneSocket !== null
  }));
});

const wss = new WebSocket.Server({ server });

wss.on('connection', (ws, req) => {
  const clientIP = req.socket.remoteAddress.replace('::ffff:', '');
  console.log('[连接] 新客户端: ' + clientIP);

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data);

      // 手机端握手验证
      if (msg.type === 'phone_hello') {
        if (msg.pin !== PIN_CODE) {
          ws.send(JSON.stringify({ type: 'auth_fail', reason: '配对码错误' }));
          ws.close();
          console.log('[拒绝] 配对码错误: ' + msg.pin);
          return;
        }
        if (phoneSocket && phoneSocket.readyState === WebSocket.OPEN) {
          phoneSocket.send(JSON.stringify({ type: 'kicked', reason: '有新设备连接' }));
          phoneSocket.close();
        }
        phoneSocket = ws;
        ws.isPhone = true;
        ws.send(JSON.stringify({ type: 'auth_ok', message: '配对成功！' }));
        _notifyUIStatus(true, clientIP);
        console.log('[配对] 手机连接成功: ' + clientIP);
        return;
      }

      // 手机回报拨号结果
      if (msg.type === 'dial_result') {
        console.log('[结果] ' + msg.number + ': ' + msg.status);
        [mainWindow, floatBarWindow].forEach(win => {
          if (win && !win.isDestroyed()) {
            try { win.webContents.send('dial-result', msg); } catch (e) {}
          }
        });
        return;
      }

      // 心跳
      if (msg.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong' }));
        return;
      }

    } catch (e) {
      console.error('[错误] 解析消息失败:', e.message);
    }
  });

  ws.on('close', () => {
    if (ws.isPhone) {
      phoneSocket = null;
      _notifyUIStatus(false, null);
      console.log('[断开] 手机断开连接: ' + clientIP);
    }
  });

  ws.on('error', (err) => {
    console.error('[WS错误]', err.message);
  });
});

function _notifyUIStatus(connected, phoneIP) {
  const data = { connected, phoneIP };
  [mainWindow, floatBarWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      try { win.webContents.send('status-update', data); } catch (e) {}
    }
  });
}

// ==================== UDP 广播发现服务 ====================
const udpSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

udpSocket.on('error', (err) => {
  console.error('[UDP错误]', err.message);
});

udpSocket.on('message', (msg, rinfo) => {
  try {
    const data = JSON.parse(msg.toString());
    if (data.type === 'discover' && data.pin === PIN_CODE) {
      const reply = JSON.stringify({
        type: 'found',
        pin: PIN_CODE,
        ip: LOCAL_IP,
        port: PORT
      });
      udpSocket.send(reply, rinfo.port, rinfo.address);
      console.log('[发现] 手机 ' + rinfo.address + ' 查询配对码 ' + data.pin + '，已回复');
    }
  } catch (e) {}
});

udpSocket.bind(DISCOVERY_PORT, '0.0.0.0', () => {
  udpSocket.setBroadcast(true);
  console.log('[发现] UDP广播服务已启动，端口: ' + DISCOVERY_PORT);
});

function startBroadcast() {
  const msg = JSON.stringify({
    type: 'announce',
    pin: PIN_CODE,
    ip: LOCAL_IP,
    port: PORT
  });
  setInterval(() => {
    try {
      udpSocket.send(msg, DISCOVERY_PORT, '255.255.255.255');
    } catch (e) {}
  }, 3000);
}

// ==================== 防火墙检查 ====================
let firewallWarning = false;

function tryAddFirewallRule() {
  exec(
    'netsh advfirewall firewall add rule name="AutoDial" dir=in action=allow protocol=TCP localport=' + PORT + ' profile=any description=AutoDial一键拨号 2>nul & ' +
    'netsh advfirewall firewall add rule name="AutoDial UDP" dir=in action=allow protocol=UDP localport=' + DISCOVERY_PORT + ' profile=any description=AutoDial一键拨号 2>nul',
    (err) => {
      if (err) {
        console.log('[防火墙] 自动添加失败（需要管理员权限）');
        firewallWarning = true;
      } else {
        console.log('[防火墙] 入站规则已添加');
      }
    }
  );
}

// ==================== 启动 ====================
app.whenReady().then(() => {
  tryAddFirewallRule();

  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      console.error('[错误] 端口 ' + PORT + ' 已被占用，请关闭其他 AutoDial 实例');
      // 使用 dialog 替代 mshta
      const { dialog } = require('electron');
      dialog.showErrorBox('AutoDial 错误', '端口 ' + PORT + ' 已被占用，请检查是否已有程序在运行！');
      app.quit();
    }
  });

  server.listen(PORT, '0.0.0.0', () => {
    console.log('');
    console.log('========================================');
    console.log('       AutoDial PC 端已启动');
    console.log('========================================');
    console.log('  本机IP:   ' + LOCAL_IP);
    console.log('  配对码:   ' + PIN_CODE);
    console.log('  端口:     ' + PORT);
    console.log('========================================');

    startBroadcast();
    createMainWindow();
    createFloatBarWindow();
  });
});

app.on('window-all-closed', () => {
  app.quit();
});

// 全局异常捕获
process.on('uncaughtException', (err) => {
  console.error('[未捕获异常]', err.message);
});

process.on('unhandledRejection', (reason) => {
  console.error('[未处理Promise拒绝]', reason);
});
