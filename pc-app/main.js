'use strict';

const http = require('http');
const WebSocket = require('ws');
const dgram = require('dgram');
const os = require('os');
const fs = require('fs');
const path = require('path');
const { exec, execSync } = require('child_process');
const crypto = require('crypto');

// ==================== 内嵌 HTML ====================
const HTML_CONTENT = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>AutoDial</title>
<style>
  :root {
    --gold: #C9A84C;
    --gold-light: #F0C040;
    --gold-dark: #8B6914;
    --bg: #111318;
    --bg2: #1A1D24;
    --bg3: #22262F;
    --text: #E8DCC8;
    --text2: #A09070;
    --green: #2ECC71;
    --red: #E74C3C;
  }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body {
    width: 100%; height: 100%;
    background: var(--bg);
    color: var(--text);
    font-family: 'Segoe UI', 'Microsoft YaHei', sans-serif;
    overflow: hidden;
    user-select: none;
  }
  .wrap {
    width: 100%; height: 100%;
    display: flex;
    flex-direction: column;
  }

  /* ---- 顶部状态栏 ---- */
  .header {
    background: var(--bg2);
    border-bottom: 1px solid var(--gold-dark);
    padding: 10px 14px;
    display: flex;
    align-items: center;
    gap: 10px;
    flex-shrink: 0;
  }
  .logo {
    font-size: 15px;
    font-weight: 700;
    color: var(--gold-light);
    letter-spacing: 2px;
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .logo-icon {
    width: 24px; height: 24px;
    background: linear-gradient(135deg, var(--gold), var(--gold-dark));
    border-radius: 6px;
    display: flex; align-items: center; justify-content: center;
    font-size: 13px;
  }
  .chips {
    display: flex;
    gap: 6px;
    flex: 1;
    justify-content: center;
  }
  .chip {
    background: var(--bg);
    border: 1px solid var(--gold-dark);
    border-radius: 6px;
    padding: 4px 10px;
    text-align: center;
  }
  .chip .lbl { font-size: 9px; color: var(--text2); letter-spacing: 1px; text-transform: uppercase; }
  .chip .val { font-size: 13px; font-weight: 700; color: var(--gold-light); letter-spacing: 2px; }
  .chip .val.ip { font-size: 12px; letter-spacing: 0.5px; }
  .status-dot {
    width: 8px; height: 8px;
    border-radius: 50%;
    background: var(--red);
    flex-shrink: 0;
  }
  .status-dot.on {
    background: var(--green);
    box-shadow: 0 0 6px var(--green);
    animation: pulse 1.5s infinite;
  }
  @keyframes pulse { 0%,100%{opacity:1}50%{opacity:0.4} }
  .status-text { font-size: 11px; color: var(--text2); white-space: nowrap; }

  /* ---- 连接成功横幅 ---- */
  .banner {
    display: none;
    background: rgba(46,204,113,0.12);
    border-bottom: 1px solid var(--green);
    padding: 6px 14px;
    text-align: center;
    font-size: 13px;
    font-weight: 700;
    color: var(--green);
    letter-spacing: 1px;
    flex-shrink: 0;
  }
  .banner.show { display: block; }

  /* ---- 主内容区 ---- */
  .body {
    flex: 1;
    padding: 12px 14px 8px;
    display: flex;
    flex-direction: column;
    gap: 10px;
    overflow: hidden;
  }

  /* 号码输入框 */
  .number-box {
    background: var(--bg2);
    border: 2px solid var(--gold-dark);
    border-radius: 10px;
    padding: 10px 14px;
    flex-shrink: 0;
  }
  .number-box:focus-within { border-color: var(--gold); }
  .number-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 4px;
  }
  .number-lbl { font-size: 9px; color: var(--text2); letter-spacing: 1px; text-transform: uppercase; }
  .clip-hint { font-size: 9px; color: var(--gold); }
  .clip-hint.flash { animation: flash 0.4s ease; }
  @keyframes flash { 50%{ color: var(--gold-light); } }
  .number-input {
    background: none; border: none; outline: none;
    font-size: 24px; font-weight: 700;
    color: var(--text); width: 100%;
    letter-spacing: 3px; caret-color: var(--gold);
  }
  .number-input::placeholder { color: var(--text2); font-size: 14px; font-weight: 400; letter-spacing: 1px; }
  .number-actions { display: flex; gap: 6px; margin-top: 6px; }
  .btn-sm {
    background: none;
    border-radius: 5px;
    padding: 4px 10px;
    font-size: 11px;
    cursor: pointer;
    transition: all 0.15s;
  }
  .btn-clear { border: 1px solid var(--bg3); color: var(--text2); }
  .btn-clear:hover { border-color: var(--red); color: var(--red); }

  /* 拨号盘 */
  .dialpad {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 7px;
    flex-shrink: 0;
  }
  .dial-btn {
    background: var(--bg2);
    border: 1px solid var(--bg3);
    border-radius: 10px;
    padding: 10px 0;
    cursor: pointer;
    transition: all 0.12s;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 1px;
  }
  .dial-btn:hover { background: var(--bg3); border-color: var(--gold-dark); }
  .dial-btn:active { transform: scale(0.93); background: rgba(201,168,76,0.1); border-color: var(--gold); }
  .dial-btn .num { font-size: 18px; font-weight: 600; color: var(--text); line-height: 1; }
  .dial-btn .sub { font-size: 7px; color: var(--text2); letter-spacing: 2px; text-transform: uppercase; }
  .dial-btn.del .num { font-size: 16px; color: var(--red); }

  /* 操作按钮 */
  .action-row { display: flex; gap: 8px; flex-shrink: 0; }
  .call-btn {
    flex: 1;
    padding: 14px;
    background: linear-gradient(135deg, #27AE60, #1E8449);
    border: none; border-radius: 12px;
    color: white; font-size: 16px; font-weight: 700;
    cursor: pointer; transition: all 0.2s;
    display: flex; align-items: center; justify-content: center; gap: 8px;
    letter-spacing: 1px;
  }
  .call-btn:hover:not(:disabled) { background: linear-gradient(135deg, #2ECC71, #27AE60); box-shadow: 0 6px 18px rgba(39,174,96,0.35); }
  .call-btn:disabled { background: var(--bg3); color: var(--text2); cursor: not-allowed; opacity: 0.6; }
  .hangup-btn {
    flex: 1;
    padding: 14px;
    background: linear-gradient(135deg, #C0392B, #96281B);
    border: none; border-radius: 12px;
    color: white; font-size: 16px; font-weight: 700;
    cursor: pointer; transition: all 0.2s;
    display: flex; align-items: center; justify-content: center; gap: 8px;
    letter-spacing: 1px;
  }
  .hangup-btn:hover:not(:disabled) { background: linear-gradient(135deg, #E74C3C, #C0392B); }
  .hangup-btn:disabled { background: var(--bg3); color: var(--text2); cursor: not-allowed; opacity: 0.4; }

  /* ---- 日志区（折叠） ---- */
  .log-section { flex-shrink: 0; }
  .log-toggle {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 5px 10px;
    background: var(--bg2);
    border-radius: 8px;
    cursor: pointer;
    font-size: 11px;
    color: var(--text2);
    border: 1px solid var(--bg3);
    transition: border-color 0.2s;
  }
  .log-toggle:hover { border-color: var(--gold-dark); }
  .log-toggle .arrow { transition: transform 0.2s; font-size: 10px; }
  .log-toggle.open .arrow { transform: rotate(180deg); }
  .log-body {
    display: none;
    max-height: 130px;
    overflow-y: auto;
    padding: 6px 8px;
    gap: 4px;
    flex-direction: column;
    background: var(--bg2);
    border-radius: 0 0 8px 8px;
    border: 1px solid var(--bg3);
    border-top: none;
    margin-top: -1px;
  }
  .log-body.open { display: flex; }
  .log-body::-webkit-scrollbar { width: 3px; }
  .log-body::-webkit-scrollbar-thumb { background: var(--bg3); border-radius: 2px; }
  .log-entry {
    display: flex; align-items: baseline; gap: 6px;
    font-size: 11px; padding: 3px 6px;
    border-radius: 4px;
    background: var(--bg);
    border-left: 2px solid var(--bg3);
    animation: fadein 0.2s;
  }
  @keyframes fadein { from{opacity:0;} to{opacity:1;} }
  .log-entry.success { border-left-color: var(--green); }
  .log-entry.error   { border-left-color: var(--red);   }
  .log-entry.info    { border-left-color: var(--gold);  }
  .log-entry .lt { color: var(--text2); flex-shrink: 0; font-size: 10px; }
  .log-entry .lm { color: var(--text); }

  /* Toast */
  .toast {
    position: fixed; bottom: 12px; left: 50%;
    transform: translateX(-50%) translateY(80px);
    background: var(--bg3); border: 1px solid var(--gold-dark);
    border-radius: 8px; padding: 8px 18px;
    font-size: 12px; color: var(--text);
    transition: transform 0.25s; z-index: 999; white-space: nowrap;
  }
  .toast.show { transform: translateX(-50%) translateY(0); }
  .toast.success { border-color: var(--green); color: var(--green); }
  .toast.error   { border-color: var(--red);   color: var(--red);   }
</style>
</head>
<body>
<div class="wrap">
  <div class="header">
    <div class="logo"><div class="logo-icon">&#x1F4DE;</div>AutoDial</div>
    <div class="chips">
      <div class="chip"><div class="lbl">IP</div><div class="val ip" id="localIP">…</div></div>
      <div class="chip"><div class="lbl">配对码</div><div class="val" id="pinCode">----</div></div>
    </div>
    <div class="status-dot" id="statusDot"></div>
    <div class="status-text" id="statusText">等待连接</div>
  </div>

  <div class="banner" id="banner">&#x2705; 手机已连接，可以拨号</div>

  <div class="body">
    <div class="number-box">
      <div class="number-row">
        <span class="number-lbl">号码</span>
        <span class="clip-hint" id="clipHint">&#x1F4CB; 已跟随剪贴板</span>
      </div>
      <input type="text" class="number-input" id="numberInput"
             placeholder="复制号码自动出现…" maxlength="20"
             inputmode="tel" autocomplete="off">
      <div class="number-actions">
        <button class="btn-sm btn-clear" onclick="clearNumber()">清除</button>
      </div>
    </div>

    <div class="dialpad">
      <button class="dial-btn" onclick="pressKey('1')"><span class="num">1</span></button>
      <button class="dial-btn" onclick="pressKey('2')"><span class="num">2</span><span class="sub">ABC</span></button>
      <button class="dial-btn" onclick="pressKey('3')"><span class="num">3</span><span class="sub">DEF</span></button>
      <button class="dial-btn" onclick="pressKey('4')"><span class="num">4</span><span class="sub">GHI</span></button>
      <button class="dial-btn" onclick="pressKey('5')"><span class="num">5</span><span class="sub">JKL</span></button>
      <button class="dial-btn" onclick="pressKey('6')"><span class="num">6</span><span class="sub">MNO</span></button>
      <button class="dial-btn" onclick="pressKey('7')"><span class="num">7</span><span class="sub">PQRS</span></button>
      <button class="dial-btn" onclick="pressKey('8')"><span class="num">8</span><span class="sub">TUV</span></button>
      <button class="dial-btn" onclick="pressKey('9')"><span class="num">9</span><span class="sub">WXYZ</span></button>
      <button class="dial-btn" onclick="pressKey('*')"><span class="num">&#x2731;</span></button>
      <button class="dial-btn" onclick="pressKey('0')"><span class="num">0</span><span class="sub">+</span></button>
      <button class="dial-btn del" onclick="deleteLast()"><span class="num">&#x232B;</span></button>
    </div>

    <div class="action-row">
      <button class="call-btn" id="callBtn" onclick="dial()" disabled>&#x1F4DE; 拨号</button>
      <button class="hangup-btn" id="hangupBtn" onclick="hangup()" disabled>&#x1F4F5; 挂断</button>
    </div>

    <div class="log-section">
      <div class="log-toggle" id="logToggle" onclick="toggleLog()">
        <span>&#x1F4DD; 日志</span>
        <span class="arrow">&#x25BC;</span>
      </div>
      <div class="log-body" id="logBody"></div>
    </div>
  </div>
</div>
<div class="toast" id="toast"></div>
<script>
let ws = null;
let isConnected = false;
let reconnectTimer = null;
let lastClipboard = '';
let clipboardTimer = null;
let logOpen = false;

function connect() {
  if (ws && ws.readyState === WebSocket.OPEN) return;
  ws = new WebSocket('ws://localhost:' + (location.port || 35432));
  ws.onopen = () => {};
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'status_update') {
      setPhoneConnected(msg.connected, msg.phoneIP);
    } else if (msg.type === 'dial_sent') {
      addLog('success', '&#x1F4DE; 已发送: ' + msg.number);
    } else if (msg.type === 'dial_result') {
      addLog(msg.status === 'ok' ? 'success' : 'error', msg.number + ' ' + (msg.status === 'ok' ? '✓' : '✗'));
    } else if (msg.type === 'error') {
      showToast(msg.message, 'error');
      addLog('error', msg.message);
    } else if (msg.type === 'hangup_sent') {
      addLog('info', '已发送挂断');
    }
  };
  ws.onclose = () => { clearTimeout(reconnectTimer); reconnectTimer = setTimeout(connect, 2000); };
  ws.onerror = () => {};
}

function loadInfo() {
  fetch('/api/info')
    .then(r => r.json())
    .then(info => {
      document.getElementById('localIP').textContent = info.ip;
      document.getElementById('pinCode').textContent = info.pin;
      if (info.connected) setPhoneConnected(true, null);
      if (info.firewall === 'warning') addLog('error', '防火墙可能拦截连接，请以管理员运行');
    })
    .catch(() => setTimeout(loadInfo, 1000));
}

function setPhoneConnected(connected, phoneIP) {
  isConnected = connected;
  const dot = document.getElementById('statusDot');
  const txt = document.getElementById('statusText');
  const banner = document.getElementById('banner');
  document.getElementById('callBtn').disabled = !connected;
  document.getElementById('hangupBtn').disabled = !connected;
  if (connected) {
    dot.className = 'status-dot on';
    txt.textContent = phoneIP ? phoneIP : '已连接';
    banner.className = 'banner show';
    addLog('success', '手机已连接' + (phoneIP ? ' ' + phoneIP : ''));
    showToast('手机已连接！', 'success');
  } else {
    dot.className = 'status-dot';
    txt.textContent = '等待连接';
    banner.className = 'banner';
    addLog('error', '手机已断开');
  }
}

function pressKey(key) {
  const inp = document.getElementById('numberInput');
  inp.value += key; inp.focus();
}
function deleteLast() {
  const inp = document.getElementById('numberInput');
  inp.value = inp.value.slice(0, -1);
}
function clearNumber() { document.getElementById('numberInput').value = ''; }

function dial() {
  const number = document.getElementById('numberInput').value.trim().replace(/\\s/g, '');
  if (!number) { showToast('请输入号码', 'error'); return; }
  if (!isConnected) { showToast('手机未连接', 'error'); return; }
  if (!ws || ws.readyState !== WebSocket.OPEN) { showToast('服务异常', 'error'); return; }
  ws.send(JSON.stringify({ type: 'dial', number }));
  addLog('info', '发送拨号: ' + number);
}
function hangup() {
  if (!ws || ws.readyState !== WebSocket.OPEN) { showToast('服务异常', 'error'); return; }
  if (!isConnected) { showToast('手机未连接', 'error'); return; }
  ws.send(JSON.stringify({ type: 'hangup' }));
  addLog('info', '发送挂断');
  showToast('已发送挂断指令');
}

function extractPhone(text) {
  const m = text.match(/1[3-9]\\d{9}|0\\d{2,3}[-\\s]?\\d{7,8}/);
  return m ? m[0].replace(/[-\\s]/g, '') : null;
}

// 服务端读取剪贴板，1秒轮询
function pollClipboard() {
  fetch('/api/clipboard')
    .then(r => r.json())
    .then(d => {
      if (d.text && d.text !== lastClipboard) {
        lastClipboard = d.text;
        const phone = extractPhone(d.text);
        if (phone) {
          const inp = document.getElementById('numberInput');
          inp.value = phone;
          const hint = document.getElementById('clipHint');
          hint.classList.add('flash');
          setTimeout(() => hint.classList.remove('flash'), 400);
        }
      }
    })
    .catch(() => {})
    .finally(() => { clipboardTimer = setTimeout(pollClipboard, 1000); });
}

function toggleLog() {
  logOpen = !logOpen;
  document.getElementById('logToggle').classList.toggle('open', logOpen);
  document.getElementById('logBody').classList.toggle('open', logOpen);
}

function addLog(type, msg) {
  const body = document.getElementById('logBody');
  const now = new Date();
  const t = String(now.getHours()).padStart(2,'0') + ':' + String(now.getMinutes()).padStart(2,'0') + ':' + String(now.getSeconds()).padStart(2,'0');
  const el = document.createElement('div');
  el.className = 'log-entry ' + type;
  el.innerHTML = '<span class="lt">' + t + '</span><span class="lm">' + msg + '</span>';
  body.appendChild(el);
  body.scrollTop = body.scrollHeight;
  while (body.children.length > 80) body.removeChild(body.firstChild);
  // 自动展开日志
  if (!logOpen) { logOpen = true; document.getElementById('logToggle').classList.add('open'); body.classList.add('open'); }
}

let toastTimer = null;
function showToast(msg, type) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.className = 'toast show ' + (type || '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.className = 'toast'; }, 2500);
}

document.addEventListener('keydown', e => {
  if (e.key === 'Enter') dial();
  if (e.key === 'Backspace' && document.activeElement.id !== 'numberInput') deleteLast();
});

loadInfo();
connect();
pollClipboard();
</script>
</body>
</html>`;


// ==================== 生成唯一4位码 ====================
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

// ==================== 获取本机局域网IP ====================
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

// ==================== 获取子网网段 ====================
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

// ==================== UDP 广播发现服务 ====================
// 手机发送配对码到广播地址，电脑匹配后回复自己的IP
const udpSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

udpSocket.on('error', (err) => {
  console.error('[UDP错误]', err.message);
});

udpSocket.on('message', (msg, rinfo) => {
  try {
    const data = JSON.parse(msg.toString());
    // 手机发来的发现请求
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

// 定期广播自己的存在（让手机能被动发现）
let broadcastTimer = null;
function startBroadcast() {
  const msg = JSON.stringify({
    type: 'announce',
    pin: PIN_CODE,
    ip: LOCAL_IP,
    port: PORT
  });
  broadcastTimer = setInterval(() => {
    try {
      udpSocket.send(msg, DISCOVERY_PORT, '255.255.255.255');
    } catch (e) {}
  }, 3000);
}

// ==================== HTTP 服务器 ====================
let phoneSocket = null;

const server = http.createServer((req, res) => {
  // API 接口
  if (req.url === '/api/info') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({
      pin: PIN_CODE,
      ip: LOCAL_IP,
      port: PORT,
      connected: phoneSocket !== null,
      hostname: os.hostname(),
      firewall: firewallWarning ? 'warning' : 'ok'
    }));
    return;
  }

  // 剪贴板接口（Node.js 读取系统剪贴板，绕过浏览器安全限制）
  if (req.url === '/api/clipboard') {
    try {
      let text = '';
      if (process.platform === 'win32') {
        text = execSync('powershell -command "Get-Clipboard"', { timeout: 1000, encoding: 'utf8' }).trim();
      } else if (process.platform === 'darwin') {
        text = execSync('pbpaste', { timeout: 1000, encoding: 'utf8' }).trim();
      } else {
        text = execSync('xclip -selection clipboard -o 2>/dev/null || xdotool type --clearmodifiers --file -', { timeout: 1000, encoding: 'utf8' }).trim();
      }
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ text }));
    } catch (e) {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ text: '' }));
    }
    return;
  }

  // 所有页面请求返回内嵌HTML
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(HTML_CONTENT);
});

// ==================== WebSocket 服务器 ====================
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
        notifyUIStatus(true, clientIP);
        console.log('[配对] 手机连接成功: ' + clientIP);
        return;
      }

      // 网页端发拨号指令
      if (msg.type === 'dial') {
        if (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'error', message: '手机未连接' }));
          return;
        }
        phoneSocket.send(JSON.stringify({
          type: 'dial',
          number: msg.number
        }));
        console.log('[拨号] ' + msg.number);
        ws.send(JSON.stringify({ type: 'dial_sent', number: msg.number }));
        return;
      }

      // 手机回报拨号结果
      if (msg.type === 'dial_result') {
        console.log('[结果] ' + msg.number + ': ' + msg.status);
        notifyUIDialResult(msg);
        return;
      }

      // 电脑端发挂断指令
      if (msg.type === 'hangup') {
        if (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'error', message: '手机未连接' }));
          return;
        }
        phoneSocket.send(JSON.stringify({ type: 'hangup' }));
        console.log('[挂断] 电脑端发送挂断指令');
        ws.send(JSON.stringify({ type: 'hangup_sent' }));
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
      notifyUIStatus(false, null);
      console.log('[断开] 手机断开连接: ' + clientIP);
    }
  });

  ws.on('error', (err) => {
    console.error('[WS错误]', err.message);
  });
});

function notifyUIStatus(connected, phoneIP) {
  wss.clients.forEach(client => {
    if (!client.isPhone && client.readyState === WebSocket.OPEN) {
      client.send(JSON.stringify({
        type: 'status_update',
        connected,
        phoneIP
      }));
    }
  });
}

function notifyUIDialResult(result) {
  wss.clients.forEach(client => {
    if (!client.isPhone && client.readyState === WebSocket.OPEN) {
      client.send(JSON.stringify({ type: 'dial_result', ...result }));
    }
  });
}

// ==================== 防火墙检查 ====================
function checkFirewall() {
  return new Promise((resolve) => {
    exec('netsh advfirewall firewall show rule name="AutoDial"', (err, stdout) => {
      if (stdout && stdout.includes('AutoDial') && stdout.includes('35432')) {
        resolve(true); // 规则已存在
      } else {
        resolve(false); // 没有规则
      }
    });
  });
}

// 启动时静默尝试添加防火墙规则
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
        checkFirewall().then(ok => { if (!ok) firewallWarning = true; });
      }
    }
  );
}

let firewallWarning = false;
tryAddFirewallRule();

// ==================== 启动服务器 ====================
server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error('[错误] 端口 ' + PORT + ' 已被占用，请关闭其他 AutoDial 实例');
    exec('mshta vbscript:msgbox("AutoDial 端口被占用，请检查是否已有程序在运行！",48,"AutoDial 错误")(window.close)');
    process.exit(1);
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

  // 启动 UDP 广播
  startBroadcast();

  // 用 Chrome/Edge App 模式打开（去掉地址栏，像桌面应用）
  const url = 'http://localhost:' + PORT;
  let opened = false;

  // 尝试 Edge App 模式
  const edgePaths = [
    process.env['LOCALAPPDATA'] + '\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
  ];
  for (const edgePath of edgePaths) {
    if (fs.existsSync(edgePath)) {
  exec('"' + edgePath + '" --app="' + url + '" --window-size=420,780 --no-first-run --disable-extensions', (err) => {
        if (err) {
          // Edge 失败，fallback 到默认浏览器
          exec('start "" "' + url + '"');
        }
      });
      opened = true;
      break;
    }
  }

  // 尝试 Chrome App 模式
  if (!opened) {
    const chromePaths = [
      process.env['LOCALAPPDATA'] + '\\Google\\Chrome\\Application\\chrome.exe',
      process.env['PROGRAMFILES'] + '\\Google\\Chrome\\Application\\chrome.exe',
      process.env['ProgramFiles(x86)'] + '\\Google\\Chrome\\Application\\chrome.exe'
    ];
    for (const chromePath of chromePaths) {
      if (fs.existsSync(chromePath)) {
        exec('"' + chromePath + '" --app="' + url + '" --window-size=420,780 --no-first-run --disable-extensions', (err) => {
          if (err) {
            exec('start "" "' + url + '"');
          }
        });
        opened = true;
        break;
      }
    }
  }

  // 最终 fallback
  if (!opened) {
    exec('start "" "' + url + '"');
  }
});

// 全局异常捕获
process.on('uncaughtException', (err) => {
  console.error('[未捕获异常]', err.message);
});

process.on('unhandledRejection', (reason) => {
  console.error('[未处理Promise拒绝]', reason);
});
