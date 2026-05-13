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
  mode: 'dark',              // 显示模式 dark/dusk/dawn/twilight/warm/mist/light
  phoneNotes: {},            // 手机备注 { "ip|name": "备注" }
  cloudServer: '',           // 云中转服务器地址，如 wss://relay.example.com:35430
  cloudEnabled: false,       // 是否启用云中转
  cloudServers: []           // 多云服务器列表，如 ["1.2.3.4:35430", "5.6.7.8:35430"]
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

// 修复：同步 cloudServer 到 cloudServers（向后兼容）
if (appSettings.cloudServer && (!Array.isArray(appSettings.cloudServers) || appSettings.cloudServers.length === 0)) {
  appSettings.cloudServers = [appSettings.cloudServer];
  console.log("[云端] 从 cloudServer 同步到 cloudServers: " + appSettings.cloudServer);
}

// 修复：如果 cloudEnabled 为 true 但实际没有配置服务器，自动清除标志
const hasConfiguredServers = Array.isArray(appSettings.cloudServers) && appSettings.cloudServers.length > 0;
if (appSettings.cloudEnabled && !hasConfiguredServers) {
  console.log("[云端] cloudEnabled=true 但没有配置服务器，清除标志");
  appSettings.cloudEnabled = false;
}

// 保存修正后的配置
saveSettings(appSettings);


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

// ==================== 多手机连接管理 ====================
const PhoneConnectionManager = require('./phone-connection-manager');
// 为了向后兼容，导出 phoneDevices 和 activePhoneId 的引用
// 注意：新代码应使用 PhoneConnectionManager.phones / .activePhoneId
const phoneDevices = PhoneConnectionManager.phones;
let activePhoneId = null;  // 同步引用 PhoneConnectionManager.activePhoneId
let pluginSocket = null;         // 插件端连接

function getActivePhone() {
  const active = PhoneConnectionManager.getActivePhone();
  if (!active) return null;
  // 返回内部设备对象，附加 uuid（Map value 本身不含 uuid）
  const dev = PhoneConnectionManager.phones.get(active.uuid);
  if (!dev) return null;
  dev._uuid = active.uuid;
  dev.uuid = active.uuid;
  return dev;
}

// 统一发送消息给手机（通过 PhoneConnectionManager，自动判断 LAN/云端）
function sendToPhone(phoneOrUuid, msg) {
  const uuid = typeof phoneOrUuid === 'string' ? phoneOrUuid : (phoneOrUuid && (phoneOrUuid.uuid || phoneOrUuid._uuid));
  if (!uuid) return false;
  return PhoneConnectionManager.sendToPhone(uuid, msg);
}

function getPhoneList() {
  return PhoneConnectionManager.getPhoneList();
}

// 备注持久化：存储在 settings.phoneNotes 中，key 为 "ip|name"
function getPhoneNoteKey(ip, name) {
  return ip + '|' + name;
}
function loadPhoneNote(ip, name) {
  const notes = appSettings.phoneNotes || {};
  return notes[getPhoneNoteKey(ip, name)] || '';
}
function savePhoneNote(ip, name, note) {
  if (!appSettings.phoneNotes) appSettings.phoneNotes = {};
  appSettings.phoneNotes[getPhoneNoteKey(ip, name)] = note;
  saveSettings(appSettings);
}
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
        port: PORT,
        cloudEnabled: appSettings.cloudEnabled,
        cloudServer: appSettings.cloudServer,
        cloudConnected: cloudConnected
      });
      // 如果有手机已连接，补发完整列表
      if (phoneDevices.size > 0) {
        mainWindow.webContents.send('phones-update', { phones: getPhoneList(), activeId: activePhoneId });
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
    connected: phoneDevices.size > 0,
    phoneCount: phoneDevices.size,
    hostname: os.hostname(),
    firewall: firewallWarning ? 'warning' : 'ok',
    cloudEnabled: appSettings.cloudEnabled,
    cloudServer: appSettings.cloudServer,
    cloudConnected: cloudConnected
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

// 拨号指令（Bug2修复: 使用ACK确认机制）
ipcMain.on('dial', (event, number) => {
  const active = getActivePhone();
  if (!active) {
    _sendError(event, '手机未连接');
    return;
  }
  const uuid = active.uuid || active._uuid;
  console.log('[拨号] ' + number + ' → ' + active.name + (active.isCloud ? ' (云端)' : ' (' + active.ip + ')'));
  // Bug2修复: 用 sendToPhoneWithAck 替代 sendToPhone，确保手机确认收到
  PhoneConnectionManager.sendToPhoneWithAck(uuid, { type: 'dial', number }).then(acked => {
    if (acked) {
      console.log('[拨号] ACK已确认: ' + number);
    } else {
      console.log('[拨号] ACK超时，手机可能未收到: ' + number);
    }
  });
  // 通知 UI 拨号已发送
  [mainWindow, floatBarWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      try { win.webContents.send('dial-sent', { number, phoneId: activePhoneId }); } catch (e) {}
    }
  });
});

// 挂断指令（Bug2修复: 使用ACK确认机制）
ipcMain.on('hangup', (event) => {
  const active = getActivePhone();
  if (!active) {
    _sendError(event, '手机未连接');
    return;
  }
  const uuid = active.uuid || active._uuid;
  console.log('[挂断] 电脑端发送挂断指令 → ' + active.name);
  PhoneConnectionManager.sendToPhoneWithAck(uuid, { type: 'hangup' }).then(acked => {
    if (acked) {
      console.log('[挂断] ACK已确认');
    } else {
      console.log('[挂断] ACK超时，手机可能未收到');
    }
  });
  [mainWindow, floatBarWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      try { win.webContents.send('hangup-sent'); } catch (e) {}
    }
  });
});

// 发送短信指令（Bug2修复: 使用ACK确认机制）
ipcMain.on('send-sms', (event, data) => {
  const active = getActivePhone();
  if (!active) {
    _sendError(event, '手机未连接');
    return;
  }
  const uuid = active.uuid || active._uuid;
  console.log('[短信] 发送短信请求: ' + data.number + ', 内容长度=' + data.content.length + ' → ' + active.name);
  PhoneConnectionManager.sendToPhoneWithAck(uuid, { type: 'sms', number: data.number, content: data.content }).then(acked => {
    if (acked) {
      console.log('[短信] ACK已确认: ' + data.number);
    } else {
      console.log('[短信] ACK超时，手机可能未收到: ' + data.number);
    }
  });
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
    try { smsWindow.webContents.send('status-update', { connected: phoneDevices.size > 0, phoneIP: null }); } catch (e) {}
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
      smsWindow.webContents.send('status-update', { connected: phoneDevices.size > 0, phoneIP: null });
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

// 悬浮横条精确尺寸调整（右键菜单展开/收起用）
ipcMain.on('floatbar-resize-to', (event, size) => {
  if (!floatBarWindow || floatBarWindow.isDestroyed()) return;
  if (size && size.width && size.height) {
    floatBarWindow.setSize(Math.round(size.width), Math.round(size.height));
  }
});

// 悬浮横条右键原生菜单
ipcMain.on('floatbar-context-menu', (event, { x, y, number }) => {
  if (!floatBarWindow || floatBarWindow.isDestroyed()) return;
  const template = [
    {
      label: '🏠 显示主窗口',
      click: () => { if (mainWindow && !mainWindow.isDestroyed()) { mainWindow.show(); mainWindow.focus(); } }
    },
    {
      label: '⚙ 打开设置',
      click: () => { if (mainWindow && !mainWindow.isDestroyed()) { mainWindow.show(); mainWindow.focus(); mainWindow.webContents.send('open-settings-tab'); } }
    },
    { type: 'separator' },
    {
      label: number ? '📞 拨打 ' + number : '📞 拨打号码',
      click: () => { floatBarWindow.webContents.send('menu-dial'); }
    },
    {
      label: '📵 挂断通话',
      click: () => { floatBarWindow.webContents.send('menu-hangup'); }
    },
    {
      label: '💬 发送短信',
      click: () => { if (mainWindow && !mainWindow.isDestroyed()) { mainWindow.show(); mainWindow.focus(); mainWindow.webContents.send('open-sms-tab'); } }
    },
    { type: 'separator' },
    {
      label: '🌵 隐藏悬浮条',
      click: () => { floatBarWindow.hide(); }
    },
  ];
  const menu = Menu.buildFromTemplate(template);
  menu.popup(floatBarWindow, { x: 0, y: 48 });
});

// 获取当前缩放值
ipcMain.handle('floatbar-get-scale', () => {
  return floatBarScale;
});

// 选择活跃手机
ipcMain.on('select-phone', (event, id) => {
  if (!phoneDevices.has(id)) return;
  activePhoneId = id;
  const dev = phoneDevices.get(id);
  console.log('[切换] 活跃手机: ' + dev.name + ' (' + dev.ip + ')');
  _notifyPhonesUpdate();
});

// 修改手机备注
ipcMain.on('rename-phone', (event, { id, note }) => {
  const dev = phoneDevices.get(id);
  if (!dev) return;
  dev.note = note;
  savePhoneNote(dev.ip, dev.name, note);
  console.log('[备注] ' + dev.name + ' → ' + note);
  _notifyPhonesUpdate();
});

// 云端配置更新
ipcMain.on('update-cloud-config', (event, { enabled, server, servers }) => {
  appSettings.cloudEnabled = !!enabled;
  if (server !== undefined) appSettings.cloudServer = server;
  if (servers !== undefined) appSettings.cloudServers = servers;
  // 同步：如果有多个服务器，cloudServer 保存第一个（向后兼容）
  if (Array.isArray(servers) && servers.length > 0 && !server) {
    appSettings.cloudServer = servers[0];
  }
  saveSettings(appSettings);
  console.log('[云端] 配置更新: enabled=' + appSettings.cloudEnabled + ' servers=' + JSON.stringify(appSettings.cloudServers) + ' server=' + appSettings.cloudServer);

  if (appSettings.cloudEnabled) {
    const serverList = Array.isArray(appSettings.cloudServers) && appSettings.cloudServers.length > 0
      ? appSettings.cloudServers
      : (appSettings.cloudServer ? [appSettings.cloudServer] : []);
    if (serverList.length > 0) {
      connectCloudServersFromList(serverList, 0);
    }
  } else {
    disconnectCloudServer();
  }
});

// 获取云端状态
ipcMain.handle('get-cloud-status', async () => {
  return {
    enabled: appSettings.cloudEnabled,
    server: appSettings.cloudServer,
    servers: appSettings.cloudServers || [],
    connected: cloudConnected
  };
});

// 测试云端服务器连通性
ipcMain.handle('test-cloud-servers', async (event, servers) => {
  const results = [];
  if (!Array.isArray(servers)) return results;
  const net = require('net');
  for (let i = 0; i < servers.length; i++) {
    const addr = servers[i];
    try {
      let url = addr;
      if (!url.startsWith('ws://') && !url.startsWith('wss://')) url = 'ws://' + url;
      const u = new URL(url);
      const host = u.hostname;
      const port = parseInt(u.port) || (url.startsWith('wss://') ? 443 : 80);
      if (!host) {
        results.push({ addr, ok: false, ms: 0, error: '地址格式错误' });
        continue;
      }
      const start = Date.now();
      await new Promise((resolve) => {
        const sock = new net.Socket();
        sock.setTimeout(3000);
        sock.on('connect', () => {
          const ms = Date.now() - start;
          sock.destroy();
          resolve({ ok: true, ms });
        });
        sock.on('timeout', () => {
          sock.destroy();
          resolve({ ok: false, ms: 0, error: '超时' });
        });
        sock.on('error', () => {
          resolve({ ok: false, ms: 0, error: '不可连接' });
        });
        sock.connect(port, host);
      });
      results.push({ addr, ok: true, ms: Date.now() - start });
    } catch (e) {
      results.push({ addr, ok: false, ms: 0, error: e.message });
    }
  }
  return results;
});

// 连接到指定云端服务器（手动切换）
ipcMain.on('connect-cloud-specific', (event, serverUrl) => {
  if (!serverUrl || !appSettings.cloudEnabled) return;
  appSettings.cloudServer = serverUrl;
  saveSettings(appSettings);
  connectCloudServer(serverUrl);
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
    
    // 转发给活跃手机端拨号
    const active = getActivePhone();
    if (!active) {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ success: false, error: '手机未连接' }));
      console.log('[HTTP拨号] 失败：手机未连接');
      return;
    }

    // Bug2修复: HTTP拨号也用ACK确认
    const uuid = active.uuid || active._uuid;
    PhoneConnectionManager.sendToPhoneWithAck(uuid, { type: 'dial', number }).then(acked => {
      console.log('[HTTP拨号] ' + number + ' (来自浏览器插件，已写入剪贴板)' + (acked ? ' ACK已确认' : ' ACK超时'));
    });

    // 同时将号码写入剪贴板
    try { clipboard.writeText(number); } catch (e) {}

    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    res.end(JSON.stringify({ success: true, number: number }));
    console.log('[HTTP拨号] ' + number + ' (来自浏览器插件，已写入剪贴板)');
    return;
  }
  
  // 打开主窗口接口 - 浏览器插件调用
  if (url.pathname === '/open') {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.show();
      mainWindow.focus();
      console.log('[HTTP] 插件请求打开主窗口');
    }
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    res.end(JSON.stringify({ success: true }));
    return;
  }

  // 切换悬浮横条显示/隐藏 - 浏览器插件调用
  if (url.pathname === '/toggle-floatbar') {
    const show = url.searchParams.get('show'); // 'true'/'false'，不传则切换
    if (!floatBarWindow) {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
      res.end(JSON.stringify({ success: false, error: '悬浮窗未创建' }));
      return;
    }
    let targetShow;
    if (show === 'true') targetShow = true;
    else if (show === 'false') targetShow = false;
    else targetShow = !floatBarWindow.isVisible();

    if (targetShow) floatBarWindow.show();
    else floatBarWindow.hide();

    if (mainWindow && !mainWindow.isDestroyed()) {
      try { mainWindow.webContents.send('floatbar-visible-changed', targetShow); } catch (e) {}
    }
    console.log('[HTTP] 插件切换悬浮窗:', targetShow ? '显示' : '隐藏');
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    res.end(JSON.stringify({ success: true, visible: targetShow }));
    return;
  }

  // 打开短信窗口 - 浏览器插件调用
  if (url.pathname === '/sms') {
    const number = url.searchParams.get('number') || '';
    const content = url.searchParams.get('content') || '';
    if (number) {
      // 触发 open-sms IPC，复用现有短信窗口逻辑
      ipcMain.emit('open-sms', { sender: { send: () => {} } }, { number, content });
      console.log('[HTTP] 插件请求发送短信:', number);
    }
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    res.end(JSON.stringify({ success: !!number, number }));
    return;
  }

  // 挂断电话 - 浏览器插件调用
  if (url.pathname === '/hangup') {
    const active = getActivePhone();
    if (!active) {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
      res.end(JSON.stringify({ success: false, error: '手机未连接' }));
      console.log('[HTTP挂断] 失败：手机未连接');
      return;
    }
    // Bug2修复: HTTP挂断也用ACK确认
    const hangupUuid = active.uuid || active._uuid;
    PhoneConnectionManager.sendToPhoneWithAck(hangupUuid, { type: 'hangup' }).then(acked => {
      console.log('[HTTP挂断] 插件发送挂断指令 → ' + active.name + (acked ? ' ACK已确认' : ' ACK超时'));
    });
    [mainWindow, floatBarWindow].forEach(win => {
      if (win && !win.isDestroyed()) {
        try { win.webContents.send('hangup-sent'); } catch (e) {}
      }
    });
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    res.end(JSON.stringify({ success: true }));
    return;
  }

  // 默认：返回状态信息
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({
    pin: PIN_CODE,
    ip: LOCAL_IP,
    port: PORT,
    connected: phoneDevices.size > 0,
    phoneCount: phoneDevices.size,
    phones: getPhoneList()
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
        const deviceName = msg.deviceName || ('手机-' + clientIP.slice(-3));
        const uuid = PhoneConnectionManager.generateUUID(deviceName, clientIP, false);

        // 清理同名旧连接
        if (PhoneConnectionManager.phones.has(uuid)) {
          const old = PhoneConnectionManager.phones.get(uuid);
          try { if (old.ws && old.ws !== ws) old.ws.close(); } catch (e) {}
        }

        const savedNote = loadPhoneNote(clientIP, deviceName);

        ws.isPhone = true;
        ws.deviceId = uuid;

        PhoneConnectionManager.registerPhone(uuid, {
          ip: clientIP,
          name: deviceName,
          note: savedNote,
          ws,
          isCloud: false
        });

        // 同步 activePhoneId 引用
        activePhoneId = PhoneConnectionManager.activePhoneId;

        ws.send(JSON.stringify({ type: 'auth_ok', message: '配对成功！', deviceId: uuid }));
        console.log('[配对] 手机连接成功: ' + deviceName + ' (' + clientIP + ')');
        return;
      }

      // Bug2修复: 处理手机端回的 ACK 确认
      if (msg.type === 'ack') {
        PhoneConnectionManager.updateHeartbeat(ws.deviceId);
        PhoneConnectionManager.handleAck(msg);
        return;
      }

      // 手机回报拨号结果
      if (msg.type === 'dial_result') {
        PhoneConnectionManager.updateHeartbeat(ws.deviceId);
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
        PhoneConnectionManager.updateHeartbeat(ws.deviceId);
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
        PhoneConnectionManager.updateHeartbeat(ws.deviceId);
        ws.send(JSON.stringify({ type: 'pong' }));
        return;
      }

      // ==================== 上传协议消息分发 ====================
      if (msg.type === 'file_upload_start' || msg.type === 'file_chunk' ||
          msg.type === 'file_upload_complete' || msg.type === 'file_upload_error') {
        PhoneConnectionManager.updateHeartbeat(ws.deviceId);
        const handler = {
          'file_upload_start': 'onFileUploadStart',
          'file_chunk': 'onFileChunk',
          'file_upload_complete': 'onFileUploadComplete',
          'file_upload_error': 'onFileUploadError'
        }[msg.type];
        if (handler) PhoneConnectionManager[handler](ws.deviceId, msg);
        return;
      }

      // 插件端连接（无需验证PIN）
      if (msg.type === 'plugin_hello') {
        pluginSocket = ws;
        ws.isPlugin = true;
        ws.send(JSON.stringify({ type: 'plugin_ok', message: '插件已连接', phoneConnected: PhoneConnectionManager.phones.size > 0 }));
        console.log('[插件] 浏览器插件连接成功');
        return;
      }

      // 插件发送拨号命令（Bug2修复: 使用ACK确认机制）
      if (msg.type === 'dial' && ws.isPlugin) {
        const active = PhoneConnectionManager.getActivePhone();
        if (!active) {
          ws.send(JSON.stringify({ type: 'dial_fail', reason: '手机未连接' }));
          console.log('[拒绝] 插件拨号失败：手机未连接');
          return;
        }
        // 转发拨号命令给活跃手机端（带ACK确认）
        PhoneConnectionManager.sendToPhoneWithAck(active.uuid, { type: 'dial', number: msg.number }).then(acked => {
          console.log('[插件-拨号] ' + msg.number + ' → ' + active.name + ' (来自浏览器插件)' + (acked ? ' ACK已确认' : ' ACK超时'));
        });
        // 确认给插件
        ws.send(JSON.stringify({ type: 'dial_sent', number: msg.number }));
        return;
      }

    } catch (e) {
      console.error('[错误] 解析消息失败:', e.message);
    }
  });

  ws.on('close', () => {
    if (ws.isPhone && ws.deviceId) {
      PhoneConnectionManager.removePhone(ws.deviceId, 'lan');
      activePhoneId = PhoneConnectionManager.activePhoneId;
      console.log('[断开] 手机断开连接 (LAN)');
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

function _notifyPhonesUpdate() {
  activePhoneId = PhoneConnectionManager.activePhoneId;
  const data = {
    phones: PhoneConnectionManager.getPhoneList(),
    activeId: PhoneConnectionManager.activePhoneId,
    connected: PhoneConnectionManager.phones.size > 0
  };
  // 向后兼容：也发 status-update 事件（旧 UI 依赖）
  const compatData = {
    connected: PhoneConnectionManager.phones.size > 0,
    phoneIP: PhoneConnectionManager.activePhoneId ? (PhoneConnectionManager.phones.get(PhoneConnectionManager.activePhoneId)?.ip || null) : null
  };
  [mainWindow, floatBarWindow, smsWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      try { win.webContents.send('phones-update', data); } catch (e) {}
      try { win.webContents.send('status-update', compatData); } catch (e) {}
    }
  });
}

// 导出给 PhoneConnectionManager 内部回调使用
global._notifyPhonesUpdate = _notifyPhonesUpdate;

// 定期检查手机心跳超时
setInterval(() => PhoneConnectionManager.checkHeartbeats(), 30000);

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

// ==================== 云中转连接管理 ====================
let cloudWs = null;              // 云中转 WebSocket 连接
let cloudReconnectTimer = null;  // 重连定时器
let cloudReconnectAttempt = 0;  // 重连次数（指数退避用）
let cloudConnected = false;      // 云端是否已连接
let _cloudTraversalGeneration = 0;  // 遍历代数计数器，防止重连时旧遍历和新遍历冲突

// 云端手机设备（通过云中转连接的手机，和 LAN 手机分开管理但统一在 phoneDevices 中）
// phoneDevices 中 ip 字段：LAN 手机是实际 IP，云端手机是 'cloud'

function connectCloudServer(targetServerUrl, onResult) {
  if (cloudWs) {
    try { cloudWs.close(); } catch (e) {}
    cloudWs = null;
  }

  let serverUrl = targetServerUrl || appSettings.cloudServer;
  if (!serverUrl || !appSettings.cloudEnabled) {
    console.log('[云端] 云中转未启用或未配置服务器地址');
    return;
  }
  // 自动补 ws:// 前缀
  if (!serverUrl.startsWith('ws://') && !serverUrl.startsWith('wss://')) {
    serverUrl = 'ws://' + serverUrl;
  }

  console.log('[云端] 正在连接云中转服务器: ' + serverUrl);

  try {
    cloudWs = new WebSocket(serverUrl);

    cloudWs.on('open', () => {
      console.log('[云端] 已连接到云中转服务器');
      // 发送 PC 端握手
      cloudWs.send(JSON.stringify({
        type: 'pc_hello',
        pin: PIN_CODE,
        hostname: os.hostname()
      }));
    });

    cloudWs.on('message', (data) => {
      try {
        const msg = JSON.parse(data);

        // 云端认证成功
        if (msg.type === 'pc_auth_ok') {
          cloudConnected = true;
          cloudReconnectAttempt = 0;  // 连接成功，重置重连计数
          appSettings.cloudServer = serverUrl;
          saveSettings(appSettings);
          console.log('[云端] 认证成功，PIN=' + msg.pin + '，在线手机数=' + msg.phoneCount);
          _notifyCloudStatus();
          // 如果是遍历连接模式，通知成功
          if (typeof onResult === 'function') {
            onResult(true, serverUrl);
          }
        }

        // 云端认证失败
        if (msg.type === 'pc_auth_fail') {
          cloudConnected = false;
          console.error('[云端] 认证失败: ' + (msg.reason || ''));
          _notifyCloudStatus();
          if (typeof onResult === 'function') {
            onResult(false, serverUrl);
          }
        }

        // 手机通过云端连接（phone_hello 被转发过来）
        if (msg.type === 'phone_hello') {
          const deviceName = msg.deviceName || ('云端手机');
          const uuid = PhoneConnectionManager.generateUUID(deviceName, 'cloud', true);

          PhoneConnectionManager.registerPhone(uuid, {
            ip: 'cloud',
            name: deviceName,
            note: loadPhoneNote('cloud', deviceName),
            cloudWs: cloudWs,
            cloudDeviceId: msg.deviceId,
            isCloud: true
          });

          activePhoneId = PhoneConnectionManager.activePhoneId;

          // 回复 auth_ok（通过云端转发）
          cloudWs.send(JSON.stringify({
            type: 'auth_ok',
            message: '配对成功！',
            deviceId: uuid,
            targetDevice: msg.deviceName
          }));

          console.log('[云端配对] 手机: ' + deviceName + ' (uuid=' + uuid + ')');
          return;
        }

        // Bug2修复: 处理云端转发的手机 ACK 确认
        if (msg.type === 'ack') {
          PhoneConnectionManager.updateHeartbeatByName(msg.deviceName);
          PhoneConnectionManager.handleAck(msg);
          return;
        }

        // 手机回报拨号结果（云端转发）
        if (msg.type === 'dial_result') {
          console.log('[云端结果] ' + msg.number + ': ' + msg.status);
          [mainWindow, floatBarWindow].forEach(win => {
            if (win && !win.isDestroyed()) {
              try { win.webContents.send('dial-result', msg); } catch (e) {}
            }
          });
          return;
        }

        // 手机回报短信发送结果（云端转发）
        if (msg.type === 'sms_result') {
          console.log('[云端短信结果] ' + msg.number + ': ' + msg.status);
          [mainWindow, floatBarWindow, smsWindow].forEach(win => {
            if (win && !win.isDestroyed()) {
              try { win.webContents.send('sms-result', msg); } catch (e) {}
            }
          });
          return;
        }

        // 云端心跳（手机端发送的 ping）
        if (msg.type === 'ping') {
          PhoneConnectionManager.updateHeartbeatByName(msg.deviceName);
          return;
        }

        // 云端心跳回复
        if (msg.type === 'pong') {
          return;
        }

        // 错误消息
        if (msg.type === 'error') {
          console.error('[云端] 服务器错误: ' + (msg.reason || ''));
          return;
        }

      } catch (e) {
        console.error('[云端] 解析消息失败:', e.message);
      }
    });

    cloudWs.on('close', (code, reason) => {
      if (cloudWs._cleanedUp) return;
      cloudWs._cleanedUp = true;
      cloudConnected = false;
      // 人类可读的错误说明
      let errMsg = '';
      if (code === 1005) errMsg = '连接异常终止（服务器未返回关闭帧，可能服务器端程序已退出）';
      else if (code === 1006) errMsg = '连接被异常关闭（无法连接到服务器，请检查服务器端程序和端口映射）';
      else if (code === 4000) errMsg = '心跳超时，服务器未收到客户端消息';
      else if (code) errMsg = '连接断开（code=' + code + '）';
      console.log('[云端] 连接断开 code=' + code + (errMsg ? ' 说明：' + errMsg : ''));
      // 移除所有云端手机的 cloud 通道，保留有 LAN 连接的设备
      PhoneConnectionManager.phones.forEach((dev, id) => {
        if (dev.isCloud) {
          PhoneConnectionManager.removePhone(id, 'cloud');
        }
      });
      activePhoneId = PhoneConnectionManager.activePhoneId;
      _notifyCloudStatus();
      _notifyPhonesUpdate();
      if (typeof onResult === 'function') {
        onResult(false, serverUrl);
      }
      // 自动重连
      _scheduleCloudReconnect();
    });

    cloudWs.on('error', (err) => {
      if (cloudWs._cleanedUp) return;
      cloudWs._cleanedUp = true;
      cloudConnected = false;
      console.error('[云端] 连接错误:', err.message);
      _removeCloudPhones();
      _notifyCloudStatus();
      if (typeof onResult === 'function') {
        onResult(false, serverUrl);
      }
    });

    // 定期发送心跳
    cloudWs._pingTimer = setInterval(() => {
      if (cloudWs && cloudWs.readyState === WebSocket.OPEN) {
        try { cloudWs.send(JSON.stringify({ type: 'ping' })); } catch (e) {}
      }
    }, 30000);

  } catch (e) {
    console.error('[云端] 创建连接失败:', e.message);
    if (typeof onResult === 'function') {
      onResult(false, serverUrl);
    }
  }
}

/**
 * 从云服务器列表中遍历尝试连接，成功一个即停止
 * @param {string[]} servers 服务器列表
 * @param {number} startIndex 开始尝试的索引
 */
function connectCloudServersFromList(servers, startIndex) {
  if (!Array.isArray(servers) || servers.length === 0) return;

  const thisGeneration = ++_cloudTraversalGeneration;

  function tryNext(index) {
    if (thisGeneration !== _cloudTraversalGeneration) {
      console.log('[云端] 遍历已被新连接取代，停止当前遍历');
      return;
    }
    if (index >= servers.length) {
      console.log('[云端] 所有云服务器连接失败');
      return;
    }

    const server = servers[index];
    console.log('[云端] 尝试服务器 ' + (index + 1) + '/' + servers.length + ': ' + server);

    connectCloudServer(server, function(success, url) {
      if (thisGeneration !== _cloudTraversalGeneration) return;
      if (success) {
        console.log('[云端] 服务器连接成功: ' + url);
      } else {
        console.log('[云端] 服务器连接失败: ' + url + '，尝试下一个');
        tryNext(index + 1);
      }
    });
  }

  tryNext(startIndex || 0);
}

function _scheduleCloudReconnect() {
  if (cloudReconnectTimer) clearTimeout(cloudReconnectTimer);
  if (!appSettings.cloudEnabled) return;
  // 指数退避：5s, 10s, 20s, 40s, 最大 60s
  const delay = Math.min(5000 * Math.pow(2, cloudReconnectAttempt), 60000);
  const nextAttempt = cloudReconnectAttempt + 1;
  console.log('[云端] ' + (cloudReconnectAttempt + 1) + ' 次重连，' + (delay / 1000) + ' 秒后重试...');
  cloudReconnectTimer = setTimeout(() => {
    cloudReconnectAttempt = nextAttempt;
    console.log('[云端] 尝试重连...');
    const serverList = Array.isArray(appSettings.cloudServers) && appSettings.cloudServers.length > 0
      ? appSettings.cloudServers
      : (appSettings.cloudServer ? [appSettings.cloudServer] : []);
    if (serverList.length > 0) {
      connectCloudServersFromList(serverList, 0);
    }
  }, delay);
}

function _removeCloudPhones() {
  const toRemove = [];
  PhoneConnectionManager.phones.forEach((dev, id) => {
    if (dev.isCloud) {
      toRemove.push(id);
    }
  });
  toRemove.forEach(id => {
    PhoneConnectionManager.removePhone(id, 'cloud');
  });
  activePhoneId = PhoneConnectionManager.activePhoneId;
}

function disconnectCloudServer() {
  if (cloudReconnectTimer) clearTimeout(cloudReconnectTimer);
  cloudReconnectTimer = null;
  if (cloudWs) {
    try {
      if (cloudWs._pingTimer) clearInterval(cloudWs._pingTimer);
      cloudWs.close();
    } catch (e) {}
    cloudWs = null;
  }
  cloudConnected = false;
  _removeCloudPhones();
  _notifyCloudStatus();
  _notifyPhonesUpdate();
  console.log('[云端] 已断开云中转连接');
}

// 发送消息给云端手机（通过 PhoneConnectionManager 统一处理）
// 保留此函数以兼容旧调用方式
function sendToCloudPhone(phone, msg) {
  if (!phone) return false;
  const uuid = phone._uuid || (PhoneConnectionManager.phones.forEach((dev, id) => {
    if (dev === phone) return id;
  }));
  if (!uuid) return false;
  return PhoneConnectionManager.sendToPhone(uuid, msg);
}

function _notifyCloudStatus() {
  const status = {
    enabled: appSettings.cloudEnabled,
    server: appSettings.cloudServer,
    servers: appSettings.cloudServers || [],
    connected: cloudConnected
  };
  [mainWindow, floatBarWindow, settingsWindow].forEach(win => {
    if (win && !win.isDestroyed()) {
      try { win.webContents.send('cloud-status', status); } catch (e) {}
    }
  });
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

    // 云中转：如果已配置并启用，自动连接
    if (appSettings.cloudEnabled) {
      const serverList = Array.isArray(appSettings.cloudServers) && appSettings.cloudServers.length > 0
        ? appSettings.cloudServers
        : (appSettings.cloudServer ? [appSettings.cloudServer] : []);
      if (serverList.length > 0) {
        connectCloudServersFromList(serverList, 0);
      }
    }

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
