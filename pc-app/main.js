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

// ==================== 设置管理 ====================
const SETTINGS_FILE = path.join(app.getPath('userData'), 'settings.json');
const DEFAULT_SETTINGS = {
  closeAction: 'minimize',   // 'minimize' | 'exit'
  trayExit: true,            // 托盘右键退出直接退出程序
  autoStart: false,          // 开机自启动
  silentStart: false,        // 隐藏界面启动
  theme: 'dark-gold',        // 主题ID
  mode: 'dark'               // 显示模式 dark/dusk/dawn/twilight/warm/mist/light
};

function loadSettings() {
  try {
    if (fs.existsSync(SETTINGS_FILE)) {
      return { ...DEFAULT_SETTINGS, ...JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')) };
    }
  } catch (e) {}
  return { ...DEFAULT_SETTINGS };
}

function saveSettings(settings) {
  try {
    fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2), 'utf8');
  } catch (e) {}
}

let appSettings = loadSettings();

const { clipboard } = require('electron');

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

let phoneSocket = null;  // 手机端连接
let pluginSocket = null; // 插件端连接
let phoneIP = null;
let mainWindow = null;
let floatBarWindow = null;
let settingsWindow = null;
let smsWindow = null;
let tray = null;
let floatBarScale = 1.0;
const FLOATBAR_MIN_SCALE = 0.7;
const FLOATBAR_MAX_SCALE = 1.5;
const FLOATBAR_MIN_W = 280;  // 最小宽度，防止内容挤压

// 开机自启动（注册表方式，无需管理员权限）
function setAutoStart(enable) {
  const appPath = app.getPath('exe');
  const regKey = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run';
  const regName = 'AutoDial';
  if (enable) {
    exec(`reg add "${regKey}" /v "${regName}" /d "${appPath}" /f`, (err) => {
      if (err) console.error('[自启动] 设置失败:', err.message);
      else console.log('[自启动] 已开启');
    });
  } else {
    exec(`reg delete "${regKey}" /v "${regName}" /f`, (err) => {
      if (err) console.error('[自启动] 取消失败:', err.message);
      else console.log('[自启动] 已关闭');
    });
  }
}

// ==================== 窗口创建 ====================

// 创建托盘图标
function createTray() {
  // 程序化生成 16x16 金色电话图标 PNG
  function createTrayIconPNG() {
    // 简单的 16x16 金色电话机位图，用 RGBA 手动编码
    // 背景: 透明, 电话听筒: 金色 (#C9A84C)
    // 16x16 像素, 每行16像素, RGBA每像素4字节
    const W = 16, H = 16;
    const pixels = Buffer.alloc(W * H * 4, 0); // 全透明

    function setPixel(x, y, r, g, b, a) {
      if (x < 0 || x >= W || y < 0 || y >= H) return;
      const i = (y * W + x) * 4;
      pixels[i] = r; pixels[i+1] = g; pixels[i+2] = b; pixels[i+3] = a;
    }

    // 绘制金色电话听筒（简化的电话图标）
    const GOLD = [201, 168, 76, 255];
    const DARK = [139, 105, 20, 255];

    // 听筒主体 - 上半部分
    for (let y = 3; y <= 8; y++) {
      for (let x = 4; x <= 11; x++) {
        setPixel(x, y, ...GOLD);
      }
    }
    // 听筒耳机部分 - 左上
    for (let y = 2; y <= 5; y++) {
      for (let x = 3; x <= 5; x++) {
        setPixel(x, y, ...DARK);
      }
    }
    // 听筒耳机部分 - 右上
    for (let y = 2; y <= 5; y++) {
      for (let x = 10; x <= 12; x++) {
        setPixel(x, y, ...DARK);
      }
    }
    // 听筒底部弧线
    for (let x = 5; x <= 10; x++) {
      setPixel(x, 9, ...GOLD);
    }
    for (let x = 6; x <= 9; x++) {
      setPixel(x, 10, ...GOLD);
    }
    setPixel(7, 11, ...GOLD);
    setPixel(8, 11, ...GOLD);
    // 底座
    for (let x = 4; x <= 11; x++) {
      setPixel(x, 12, ...DARK);
      setPixel(x, 13, ...DARK);
    }

    // 编码为 PNG（最小有效PNG）
    const { createHash } = require('crypto');
    const zlib = require('zlib');

    // 构造原始图像数据（每行前加filter byte 0）
    const rawData = Buffer.alloc(H * (1 + W * 4));
    for (let y = 0; y < H; y++) {
      rawData[y * (1 + W * 4)] = 0; // filter: None
      pixels.copy(rawData, y * (1 + W * 4) + 1, y * W * 4, (y + 1) * W * 4);
    }
    const compressed = zlib.deflateSync(rawData);

    // PNG 文件结构
    function crc32(buf) {
      const table = new Int32Array(256);
      for (let i = 0; i < 256; i++) {
        let c = i;
        for (let j = 0; j < 8; j++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        table[i] = c;
      }
      let crc = 0xFFFFFFFF;
      for (let i = 0; i < buf.length; i++) crc = table[(crc ^ buf[i]) & 0xFF] ^ (crc >>> 8);
      return (crc ^ 0xFFFFFFFF) >>> 0;
    }

    function chunk(type, data) {
      const len = Buffer.alloc(4);
      len.writeUInt32BE(data.length);
      const typeAndData = Buffer.concat([Buffer.from(type), data]);
      const crcBuf = Buffer.alloc(4);
      crcBuf.writeUInt32BE(crc32(typeAndData));
      return Buffer.concat([len, typeAndData, crcBuf]);
    }

    const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

    // IHDR
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(W, 0);
    ihdr.writeUInt32BE(H, 4);
    ihdr[8] = 8;  // bit depth
    ihdr[9] = 6;  // color type: RGBA
    ihdr[10] = 0; // compression
    ihdr[11] = 0; // filter
    ihdr[12] = 0; // interlace

    return nativeImage.createFromBuffer(
      Buffer.concat([signature, chunk('IHDR', ihdr), chunk('IDAT', compressed), chunk('IEND', Buffer.alloc(0))]),
      { width: W, height: H }
    );
  }

  const trayIcon = createTrayIconPNG();
  tray = new Tray(trayIcon);
  tray.setToolTip('AutoDial 一键拨号');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '显示主窗口', click: () => { if (mainWindow) { mainWindow.show(); mainWindow.focus(); } } },
    { type: 'separator' },
    { label: '显示悬浮条', click: () => { if (floatBarWindow) floatBarWindow.show(); } },
    { label: '隐藏悬浮条', click: () => { if (floatBarWindow) floatBarWindow.hide(); } },
    { type: 'separator' },
    { label: '退出', click: () => { app.isQuitting = true; if (tray) { tray.destroy(); tray = null; } app.quit(); } }
  ]));

  tray.on('double-click', () => {
    if (mainWindow) {
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 420,
    height: 780,
    minWidth: 210,
    minHeight: 350,
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

  mainWindow.on('close', (e) => {
    if (!app.isQuitting) {
      if (appSettings.closeAction === 'exit') {
        // 用户设置关闭即退出
        if (tray) { tray.destroy(); tray = null; }
        return; // 允许关闭
      }
      // 默认：最小化到托盘
      e.preventDefault();
      mainWindow.hide();
      console.log('[托盘] 主窗口已最小化到托盘');
    }
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
      // 如果手机已连接，补发状态（含真实 IP）
      if (phoneSocket && phoneSocket.readyState === WebSocket.OPEN) {
        mainWindow.webContents.send('status-update', { connected: true, phoneIP: phoneIP });
      }
      // 推送当前主题设置
      mainWindow.webContents.send('theme-changed', { theme: appSettings.theme, mode: appSettings.mode });
    } catch (e) {}
  });
}

function createFloatBarWindow() {
  const primaryDisplay = screen.getPrimaryDisplay();
  const { width: screenW, height: screenH } = primaryDisplay.workAreaSize;

  // 悬浮条初始位置：紧贴主界面右边，垂直与主界面状态栏的悬浮条开关平齐
  const mainW = 420, mainH = 780;
  const barW = 440, barH = 48;
  const mainX = Math.round((screenW - mainW) / 2);
  const mainY = Math.round((screenH - mainH) / 2);
  const initialX = mainX + mainW + 8; // 主界面右边，间距 8px
  const initialY = mainY + 36 + 8; // 标题栏(36px) + 间距 8px，与状态栏开关行平齐

  floatBarWindow = new BrowserWindow({
    width: barW,
    height: barH,
    x: initialX,
    y: initialY,
    frame: false,
    transparent: true,
    resizable: true,        // 必须 true，否则 setSize 无法缩小窗口
    minimizable: false,     // 禁止最小化按钮（悬浮条不需要）
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
    // 推送当前主题设置
    try {
      floatBarWindow.webContents.send('theme-changed', { theme: appSettings.theme, mode: appSettings.mode });
    } catch (e) {}
  });
}

// ==================== IPC 处理 ====================

// 获取设置
ipcMain.handle('get-settings', async () => {
  return appSettings;
});

// 获取当前主题设置
ipcMain.handle('get-theme-setting', async () => {
  return { theme: appSettings.theme, mode: appSettings.mode };
});

// 切换主题
ipcMain.on('change-theme', (event, data) => {
  if (data.id) appSettings.theme = data.id;
  if (data.mode) appSettings.mode = data.mode;
  saveSettings(appSettings);
  console.log('[主题] ' + appSettings.theme + ' / ' + appSettings.mode);
  // 广播给所有窗口
  [mainWindow, floatBarWindow, settingsWindow, smsWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      try { win.webContents.send('theme-changed', { theme: appSettings.theme, mode: appSettings.mode }); } catch (e) {}
    }
  });
});

// 更新窗口背景色
ipcMain.on('update-bg-color', (event, color) => {
  const win = BrowserWindow.fromWebContents(event.sender);
  if (win && !win.isDestroyed()) {
    try {
      // 跳过 rgba 颜色（毛玻璃等），Electron backgroundColor 不支持透明
      if (color && !color.startsWith('rgba')) {
        win.setBackgroundColor(color);
      }
    } catch (e) {}
  }
});

// 保存单个设置项
ipcMain.on('save-setting', (event, { key, value }) => {
  appSettings[key] = value;
  saveSettings(appSettings);
  console.log('[设置] ' + key + ' = ' + value);
});

// 开机自启动
ipcMain.on('set-auto-start', (event, enable) => {
  setAutoStart(enable);
});

// 打开设置窗口
ipcMain.on('open-settings', () => {
  if (settingsWindow && !settingsWindow.isDestroyed()) {
    settingsWindow.show();
    settingsWindow.focus();
    return;
  }
  settingsWindow = new BrowserWindow({
    width: 380,
    height: 420,
    minWidth: 320,
    minHeight: 350,
    frame: false,
    transparent: false,
    backgroundColor: '#111318',
    resizable: true,
    modal: true,
    parent: mainWindow,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  settingsWindow.loadFile(path.join(__dirname, 'renderer', 'settings.html'));
  settingsWindow.setMenuBarVisibility(false);
  settingsWindow.on('closed', () => { settingsWindow = null; });
  // 推送当前主题设置
  settingsWindow.webContents.on('did-finish-load', () => {
    try {
      settingsWindow.webContents.send('theme-changed', { theme: appSettings.theme, mode: appSettings.mode });
    } catch (e) {}
  });
});

// 关闭设置窗口
ipcMain.on('close-settings', () => {
  if (settingsWindow && !settingsWindow.isDestroyed()) {
    settingsWindow.close();
  }
});

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
    const text = (clipboard.readText() || '').trim();
    return { text: text || '' };
  } catch (e) {
    return { text: '' };
  }
});

// OCR 功能已移除（tesseract.js 已卸载）

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

// 发送短信指令
ipcMain.on('send-sms', (event, data) => {
  if (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN) {
    _sendError(event, '手机未连接');
    return;
  }
  phoneSocket.send(JSON.stringify({ type: 'sms', number: data.number, content: data.content }));
  console.log('[短信] 发送短信请求: ' + data.number + ', 内容长度=' + data.content.length);
});

// 打开短信编辑窗口
// payload 可以是字符串（仅号码）或对象 {number, content}
ipcMain.on('open-sms', (event, payload) => {
  const number  = typeof payload === 'object' ? (payload.number  || '') : (payload || '');
  const content = typeof payload === 'object' ? (payload.content || '') : '';

  if (smsWindow && !smsWindow.isDestroyed()) {
    smsWindow.show();
    smsWindow.focus();
    // 传号码+内容+连接状态给短信窗口
    try { smsWindow.webContents.send('sms-number', number); } catch (e) {}
    if (content) {
      try { smsWindow.webContents.send('sms-content', content); } catch (e) {}
    }
    try { smsWindow.webContents.send('status-update', { connected: phoneSocket !== null, phoneIP: null }); } catch (e) {}
    return;
  }
  smsWindow = new BrowserWindow({
    width: 400,
    height: 580,
    minWidth: 320,
    minHeight: 480,
    frame: false,
    transparent: false,
    backgroundColor: '#111318',
    resizable: true,
    modal: false,
    parent: mainWindow,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  smsWindow.loadFile(path.join(__dirname, 'renderer', 'sms.html'));
  smsWindow.setMenuBarVisibility(false);
  smsWindow.on('closed', () => { smsWindow = null; });
  smsWindow.webContents.on('did-finish-load', () => {
    try {
      smsWindow.webContents.send('theme-changed', { theme: appSettings.theme, mode: appSettings.mode });
      smsWindow.webContents.send('sms-number', number);
      if (content) smsWindow.webContents.send('sms-content', content);
      // 同步手机连接状态
      smsWindow.webContents.send('status-update', { connected: phoneSocket !== null, phoneIP: null });
    } catch (e) {}
  });
});

// 窗口置顶
ipcMain.on('set-topmost', (event, enable) => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.setAlwaysOnTop(enable);
  }
  console.log('[置顶] ' + (enable ? '已开启' : '已关闭'));
});

// 悬浮横条显示/隐藏
ipcMain.on('toggle-floatbar', (event, show) => {
  if (!floatBarWindow) return;
  if (show) {
    floatBarWindow.show();
    console.log('[悬浮条] 已显示');
  } else {
    floatBarWindow.hide();
    console.log('[悬浮条] 已隐藏');
  }
  // 通知主界面更新开关状态
  if (mainWindow && !mainWindow.isDestroyed()) {
    try { mainWindow.webContents.send('floatbar-visible-changed', show); } catch (e) {}
  }
});

// 悬浮横条显示主窗口
ipcMain.on('floatbar-show-main', () => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.show();
    mainWindow.focus();
  }
});

// 窗口控制（最小化/关闭）
ipcMain.on('window-control', (event, action) => {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (action === 'minimize') mainWindow.minimize();
  if (action === 'close') mainWindow.close();  // 触发 close 事件，弹出选择框
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

// 悬浮横条缩放（拖拽控制 - 右下角手柄）
ipcMain.on('floatbar-resize', (event, delta) => {
  if (!floatBarWindow || floatBarWindow.isDestroyed()) return;
  const oldScale = floatBarScale;
  floatBarScale = Math.round((floatBarScale + delta * 0.1) * 10) / 10;
  floatBarScale = Math.max(FLOATBAR_MIN_SCALE, Math.min(FLOATBAR_MAX_SCALE, floatBarScale));
  if (floatBarScale === oldScale) return;

  const baseW = 440, baseH = 48;
  const newW = Math.max(FLOATBAR_MIN_W, Math.round(baseW * floatBarScale));
  const newH = Math.round(baseH * floatBarScale);
  floatBarWindow.setSize(newW, newH);
});

// 获取当前缩放值
ipcMain.handle('floatbar-get-scale', () => {
  return floatBarScale;
});

function _sendError(event, message) {
  const win = BrowserWindow.fromWebContents(event.sender);
  if (win && !win.isDestroyed()) {
    try { win.webContents.send('error', { message }); } catch (e) {}
  }
}

// ==================== HTTP/WebSocket 服务器 ====================
const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  // 所有请求统一加 CORS 头（允许浏览器插件跨域访问）
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }
  
  // HTTP拨号接口 - 插件调用
  if (url.pathname.includes('dial') && url.searchParams.has('number')) {
    const number = url.searchParams.get('number');
    
    // 验证手机号格式
    if (!/^1[3-9]\d{9}$/.test(number)) {
      res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ success: false, error: '无效的手机号' }));
      return;
    }
    
    // 转发给手机端拨号
    if (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN) {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ success: false, error: '手机未连接' }));
      console.log('[HTTP拨号] 失败：手机未连接');
      return;
    }
    
    phoneSocket.send(JSON.stringify({ type: 'dial', number }));

    // 同时将号码写入剪贴板
    try { clipboard.writeText(number); } catch (e) {}

    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    res.end(JSON.stringify({ success: true, number: number }));
    console.log('[HTTP拨号] ' + number + ' (来自浏览器插件，已写入剪贴板)');
    return;
  }
  
  // 默认：返回状态信息
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
        phoneIP = clientIP;
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

      // 手机回报短信发送结果
      if (msg.type === 'sms_result') {
        console.log('[短信结果] ' + msg.number + ': ' + msg.status);
        [mainWindow, floatBarWindow, smsWindow].forEach(win => {
          if (win && !win.isDestroyed()) {
            try { win.webContents.send('sms-result', msg); } catch (e) {}
          }
        });
        return;
      }

      // 心跳
      if (msg.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong' }));
        return;
      }

      // 插件端连接（无需验证PIN）
      if (msg.type === 'plugin_hello') {
        pluginSocket = ws;
        ws.isPlugin = true;
        ws.send(JSON.stringify({ type: 'plugin_ok', message: '插件已连接', phoneConnected: phoneSocket !== null }));
        console.log('[插件] 浏览器插件连接成功');
        return;
      }

      // 插件发送拨号命令
      if (msg.type === 'dial' && ws.isPlugin) {
        if (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'dial_fail', reason: '手机未连接' }));
          console.log('[拒绝] 插件拨号失败：手机未连接');
          return;
        }
        // 转发拨号命令给手机端
        phoneSocket.send(JSON.stringify({ type: 'dial', number: msg.number }));
        console.log('[插件-拨号] ' + msg.number + ' (来自浏览器插件)');
        // 确认给插件
        ws.send(JSON.stringify({ type: 'dial_sent', number: msg.number }));
        return;
      }

    } catch (e) {
      console.error('[错误] 解析消息失败:', e.message);
    }
  });

  ws.on('close', () => {
    if (ws.isPhone) {
      phoneSocket = null;
      phoneIP = null;
      _notifyUIStatus(false, null);
      console.log('[断开] 手机断开连接: ' + clientIP);
    }
    if (ws.isPlugin) {
      pluginSocket = null;
      console.log('[插件] 浏览器插件断开连接');
    }
  });

  ws.on('error', (err) => {
    console.error('[WS错误]', err.message);
  });
});

function _notifyUIStatus(connected, phoneIP) {
  const data = { connected, phoneIP };
  [mainWindow, floatBarWindow, smsWindow].forEach(win => {
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
    createTray();
    createMainWindow();
    createFloatBarWindow();

    // 隐藏界面启动：启动后立即隐藏主窗口
    if (appSettings.silentStart) {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.hide();
        console.log('[启动] 隐藏界面启动模式');
      }
    }

    // 同步开机自启动状态
    if (appSettings.autoStart) {
      setAutoStart(true);
    }
  });
});

app.on('window-all-closed', () => {
  // 有托盘且未标记退出，保持程序运行
  if (tray && !app.isQuitting) return;
  app.quit();
});

// 全局异常捕获
process.on('uncaughtException', (err) => {
  console.error('[未捕获异常]', err.message);
});

process.on('unhandledRejection', (reason) => {
  console.error('[未处理Promise拒绝]', reason);
});
