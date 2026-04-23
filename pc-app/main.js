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
<title>AutoDial - 一键拨号</title>
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
    --blue: #3498DB;
  }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    background: var(--bg);
    color: var(--text);
    font-family: 'Segoe UI', 'Microsoft YaHei', sans-serif;
    min-height: 100vh;
    display: flex;
    flex-direction: column;
  }
  .header {
    background: linear-gradient(135deg, var(--bg2), var(--bg3));
    border-bottom: 1px solid var(--gold-dark);
    padding: 16px 24px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }
  .logo {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 20px;
    font-weight: 700;
    color: var(--gold-light);
    letter-spacing: 2px;
  }
  .logo-icon {
    width: 32px; height: 32px;
    background: linear-gradient(135deg, var(--gold), var(--gold-dark));
    border-radius: 8px;
    display: flex; align-items: center; justify-content: center;
    font-size: 18px;
  }
  .connection-panel {
    display: flex;
    align-items: center;
    gap: 20px;
    flex: 1;
    justify-content: center;
  }
  .info-chip {
    background: var(--bg);
    border: 1px solid var(--gold-dark);
    border-radius: 8px;
    padding: 8px 16px;
    text-align: center;
    min-width: 130px;
  }
  .info-chip .label {
    font-size: 11px;
    color: var(--text2);
    margin-bottom: 4px;
    text-transform: uppercase;
    letter-spacing: 1px;
  }
  .info-chip .value {
    font-size: 18px;
    font-weight: 700;
    color: var(--gold-light);
    letter-spacing: 4px;
  }
  .info-chip .value.ip {
    font-size: 15px;
    letter-spacing: 1px;
  }
  .status-badge {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 20px;
    border-radius: 24px;
    font-weight: 600;
    font-size: 15px;
    transition: all 0.4s ease;
    border: 2px solid;
  }
  .status-badge.disconnected {
    background: rgba(231, 76, 60, 0.1);
    border-color: var(--red);
    color: var(--red);
  }
  .status-badge.connected {
    background: rgba(46, 204, 113, 0.12);
    border-color: var(--green);
    color: var(--green);
    box-shadow: 0 0 20px rgba(46, 204, 113, 0.25);
  }
  .status-dot {
    width: 10px; height: 10px;
    border-radius: 50%;
    background: currentColor;
  }
  .status-badge.connected .status-dot {
    animation: pulse-dot 1.5s infinite;
  }
  @keyframes pulse-dot {
    0%, 100% { opacity: 1; transform: scale(1); }
    50% { opacity: 0.5; transform: scale(0.7); }
  }
  .connect-banner {
    display: none;
    background: linear-gradient(135deg, rgba(46,204,113,0.15), rgba(46,204,113,0.05));
    border-bottom: 2px solid var(--green);
    padding: 14px 24px;
    text-align: center;
    font-size: 17px;
    font-weight: 700;
    color: var(--green);
    letter-spacing: 1px;
    animation: slide-in 0.4s ease;
  }
  .connect-banner.show { display: block; }
  @keyframes slide-in {
    from { transform: translateY(-100%); opacity: 0; }
    to { transform: translateY(0); opacity: 1; }
  }
  .main {
    flex: 1;
    display: flex;
    gap: 0;
    overflow: hidden;
  }
  .dial-section {
    flex: 1;
    padding: 28px;
    display: flex;
    flex-direction: column;
    gap: 20px;
    max-width: 440px;
    margin: 0 auto;
  }
  .number-box {
    background: var(--bg2);
    border: 2px solid var(--gold-dark);
    border-radius: 12px;
    padding: 16px 20px;
    transition: border-color 0.2s;
  }
  .number-box:focus-within {
    border-color: var(--gold);
    box-shadow: 0 0 0 3px rgba(201, 168, 76, 0.15);
  }
  .number-label {
    font-size: 11px;
    color: var(--text2);
    text-transform: uppercase;
    letter-spacing: 1px;
    margin-bottom: 8px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .clipboard-hint {
    font-size: 11px;
    color: var(--gold);
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .clipboard-hint.active {
    animation: flash 0.5s ease;
  }
  @keyframes flash {
    0%, 100% { color: var(--gold); }
    50% { color: var(--gold-light); }
  }
  .number-input {
    background: none;
    border: none;
    outline: none;
    font-size: 28px;
    font-weight: 600;
    color: var(--text);
    width: 100%;
    letter-spacing: 3px;
    caret-color: var(--gold);
  }
  .number-input::placeholder {
    color: var(--text2);
    font-weight: 400;
    letter-spacing: 1px;
    font-size: 16px;
  }
  .number-actions {
    display: flex;
    gap: 8px;
    margin-top: 10px;
  }
  .btn-clear {
    background: none;
    border: 1px solid var(--bg3);
    border-radius: 6px;
    color: var(--text2);
    padding: 6px 12px;
    font-size: 13px;
    cursor: pointer;
    transition: all 0.2s;
  }
  .btn-clear:hover { border-color: var(--red); color: var(--red); }
  .btn-paste {
    background: none;
    border: 1px solid var(--gold-dark);
    border-radius: 6px;
    color: var(--gold);
    padding: 6px 12px;
    font-size: 13px;
    cursor: pointer;
    transition: all 0.2s;
  }
  .btn-paste:hover { background: rgba(201,168,76,0.1); }
  .dialpad {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 10px;
  }
  .dial-btn {
    background: var(--bg2);
    border: 1px solid var(--bg3);
    border-radius: 12px;
    padding: 14px 0;
    cursor: pointer;
    transition: all 0.15s;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    user-select: none;
  }
  .dial-btn:hover {
    background: var(--bg3);
    border-color: var(--gold-dark);
    transform: translateY(-1px);
  }
  .dial-btn:active {
    transform: scale(0.95);
    background: rgba(201,168,76,0.1);
    border-color: var(--gold);
  }
  .dial-btn .num {
    font-size: 22px;
    font-weight: 600;
    color: var(--text);
    line-height: 1;
  }
  .dial-btn .sub {
    font-size: 9px;
    color: var(--text2);
    letter-spacing: 2px;
    text-transform: uppercase;
  }
  .dial-btn.del { color: var(--red); }
  .dial-btn.del .num { font-size: 20px; }
  .call-btn {
    width: 100%;
    padding: 18px;
    background: linear-gradient(135deg, #27AE60, #1E8449);
    border: none;
    border-radius: 14px;
    color: white;
    font-size: 20px;
    font-weight: 700;
    cursor: pointer;
    transition: all 0.2s;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    letter-spacing: 2px;
  }
  .call-btn:hover:not(:disabled) {
    background: linear-gradient(135deg, #2ECC71, #27AE60);
    transform: translateY(-2px);
    box-shadow: 0 8px 24px rgba(39,174,96,0.35);
  }
  .call-btn:active:not(:disabled) {
    transform: scale(0.97);
  }
  .call-btn:disabled {
    background: var(--bg3);
    color: var(--text2);
    cursor: not-allowed;
    opacity: 0.6;
  }
  .hangup-btn {
    width: 100%;
    padding: 14px;
    background: linear-gradient(135deg, #C0392B, #96281B);
    border: none;
    border-radius: 12px;
    color: white;
    font-size: 16px;
    font-weight: 700;
    cursor: pointer;
    transition: all 0.2s;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    letter-spacing: 1px;
  }
  .hangup-btn:hover:not(:disabled) {
    background: linear-gradient(135deg, #E74C3C, #C0392B);
    transform: translateY(-1px);
  }
  .hangup-btn:disabled {
    background: var(--bg3);
    color: var(--text2);
    cursor: not-allowed;
    opacity: 0.4;
  }
  .log-section {
    width: 300px;
    background: var(--bg2);
    border-left: 1px solid var(--bg3);
    display: flex;
    flex-direction: column;
  }
  .log-header {
    padding: 16px 20px;
    border-bottom: 1px solid var(--bg3);
    font-size: 13px;
    color: var(--text2);
    text-transform: uppercase;
    letter-spacing: 1px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .log-clear {
    background: none;
    border: none;
    color: var(--text2);
    cursor: pointer;
    font-size: 12px;
    padding: 2px 8px;
    border-radius: 4px;
    transition: color 0.2s;
  }
  .log-clear:hover { color: var(--red); }
  .log-list {
    flex: 1;
    overflow-y: auto;
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .log-list::-webkit-scrollbar { width: 4px; }
  .log-list::-webkit-scrollbar-track { background: transparent; }
  .log-list::-webkit-scrollbar-thumb { background: var(--bg3); border-radius: 2px; }
  .log-item {
    background: var(--bg);
    border-radius: 8px;
    padding: 10px 12px;
    border-left: 3px solid var(--bg3);
    animation: fade-in 0.3s ease;
  }
  @keyframes fade-in {
    from { opacity: 0; transform: translateX(10px); }
    to { opacity: 1; transform: translateX(0); }
  }
  .log-item.success { border-left-color: var(--green); }
  .log-item.error { border-left-color: var(--red); }
  .log-item.info { border-left-color: var(--gold); }
  .log-item .log-time {
    font-size: 11px;
    color: var(--text2);
    margin-bottom: 3px;
  }
  .log-item .log-msg {
    font-size: 13px;
    color: var(--text);
  }
  .toast {
    position: fixed;
    bottom: 24px;
    left: 50%;
    transform: translateX(-50%) translateY(100px);
    background: var(--bg3);
    border: 1px solid var(--gold-dark);
    border-radius: 10px;
    padding: 12px 24px;
    font-size: 14px;
    color: var(--text);
    transition: transform 0.3s ease;
    z-index: 1000;
    white-space: nowrap;
  }
  .toast.show { transform: translateX(-50%) translateY(0); }
  .toast.success { border-color: var(--green); color: var(--green); }
  .toast.error { border-color: var(--red); color: var(--red); }
  @media (max-width: 680px) {
    .log-section { display: none; }
    .connection-panel { flex-wrap: wrap; }
  }
</style>
</head>
<body>
<div class="header">
  <div class="logo">
    <div class="logo-icon">&#x1F4DE;</div>
    AutoDial
  </div>
  <div class="connection-panel">
    <div class="info-chip">
      <div class="label">本机 IP</div>
      <div class="value ip" id="localIP">读取中...</div>
    </div>
    <div class="info-chip">
      <div class="label">配对码</div>
      <div class="value" id="pinCode">----</div>
    </div>
  </div>
  <div class="status-badge disconnected" id="statusBadge">
    <div class="status-dot"></div>
    <span id="statusText">等待手机连接</span>
  </div>
</div>
<div class="connect-banner" id="connectBanner">
  &#x2705; &nbsp;手机已连接！可以开始拨号了
</div>
<div class="main">
  <div class="dial-section">
    <div class="number-box">
      <div class="number-label">
        <span>拨打号码</span>
        <span class="clipboard-hint" id="clipboardHint">&#x1F4CB; 自动读取剪贴板</span>
      </div>
      <input type="text" class="number-input" id="numberInput"
             placeholder="输入或复制号码..." maxlength="20"
             inputmode="tel" autocomplete="off">
      <div class="number-actions">
        <button class="btn-paste" onclick="pasteFromClipboard()">粘贴</button>
        <button class="btn-clear" onclick="clearNumber()">清除</button>
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
    <button class="call-btn" id="callBtn" onclick="dial()" disabled>
      &#x1F4DE; &nbsp;拨号
    </button>
    <button class="hangup-btn" id="hangupBtn" onclick="hangup()" disabled>
      &#x1F4F5; &nbsp;挂断
    </button>
  </div>
  <div class="log-section">
    <div class="log-header">
      <span>通话记录</span>
      <button class="log-clear" onclick="clearLog()">清空</button>
    </div>
    <div class="log-list" id="logList">
      <div class="log-item info">
        <div class="log-time">提示</div>
        <div class="log-msg">手机输入配对码即可自动连接</div>
      </div>
    </div>
  </div>
</div>
<div class="toast" id="toast"></div>
<script>
let ws = null;
let isConnected = false;
let reconnectTimer = null;

function connect() {
  if (ws && ws.readyState === WebSocket.OPEN) return;
  ws = new WebSocket('ws://localhost:' + (location.port || 35432));
  ws.onopen = () => { console.log('连接到本地服务器'); };
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'status_update') {
      setPhoneConnected(msg.connected, msg.phoneIP);
    } else if (msg.type === 'dial_sent') {
      addLog('success', '&#x1F4DE; 已发送拨号: ' + msg.number);
    } else if (msg.type === 'dial_result') {
      addLog(msg.status === 'ok' ? 'success' : 'error', msg.number + ': ' + (msg.status === 'ok' ? '拨出成功' : '拨号失败'));
    } else if (msg.type === 'error') {
      showToast(msg.message, 'error');
      addLog('error', msg.message);
    } else if (msg.type === 'hangup_sent') {
      addLog('info', '&#x1F4F5; 挂断指令已发送到手机');
    }
  };
  ws.onclose = () => {
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, 2000);
  };
  ws.onerror = () => {};
}

function loadInfo() {
  fetch('/api/info')
    .then(r => r.json())
    .then(info => {
      document.getElementById('localIP').textContent = info.ip;
      document.getElementById('pinCode').textContent = info.pin;
      if (info.connected) setPhoneConnected(true, null);
      // 防火墙警告
      if (info.firewall === 'warning') {
        addLog('error', '&#x1F534; 防火墙可能拦截了连接，请以管理员身份运行本程序一次');
      }
    })
    .catch(() => setTimeout(loadInfo, 1000));
}

function setPhoneConnected(connected, phoneIP) {
  isConnected = connected;
  const badge = document.getElementById('statusBadge');
  const text = document.getElementById('statusText');
  const banner = document.getElementById('connectBanner');
  const callBtn = document.getElementById('callBtn');
  const hangupBtn = document.getElementById('hangupBtn');
  if (connected) {
    badge.className = 'status-badge connected';
    text.textContent = phoneIP ? '已连接 ' + phoneIP : '手机已连接';
    banner.className = 'connect-banner show';
    callBtn.disabled = false;
    hangupBtn.disabled = false;
    addLog('success', '&#x2705; 手机连接成功' + (phoneIP ? ' \\u00B7 ' + phoneIP : ''));
    showToast('手机已连接！可以开始拨号了', 'success');
  } else {
    badge.className = 'status-badge disconnected';
    text.textContent = '等待手机连接';
    banner.className = 'connect-banner';
    callBtn.disabled = true;
    hangupBtn.disabled = true;
    addLog('error', '手机已断开连接');
  }
}

function pressKey(key) {
  const input = document.getElementById('numberInput');
  input.value += key;
  input.focus();
}

function deleteLast() {
  const input = document.getElementById('numberInput');
  input.value = input.value.slice(0, -1);
}

function clearNumber() {
  document.getElementById('numberInput').value = '';
}

function dial() {
  const number = document.getElementById('numberInput').value.trim().replace(/\\s/g, '');
  if (!number) { showToast('请输入号码', 'error'); return; }
  if (!isConnected) { showToast('手机未连接', 'error'); return; }
  if (!ws || ws.readyState !== WebSocket.OPEN) { showToast('服务连接异常', 'error'); return; }
  ws.send(JSON.stringify({ type: 'dial', number: number }));
  addLog('info', '&#x1F4E4; 发送拨号: ' + number);
}

function hangup() {
  if (!ws || ws.readyState !== WebSocket.OPEN) { showToast('服务连接异常', 'error'); return; }
  if (!isConnected) { showToast('手机未连接', 'error'); return; }
  ws.send(JSON.stringify({ type: 'hangup' }));
  addLog('info', '&#x1F4F5; 已发送挂断指令');
  showToast('已发送挂断指令');
}

async function pasteFromClipboard() {
  try {
    const text = await navigator.clipboard.readText();
    const phone = extractPhone(text);
    if (phone) {
      document.getElementById('numberInput').value = phone;
      const hint = document.getElementById('clipboardHint');
      hint.classList.add('active');
      setTimeout(() => hint.classList.remove('active'), 500);
      showToast('已从剪贴板读取号码');
    } else {
      showToast('剪贴板中没有识别到号码', 'error');
    }
  } catch (e) {
    showToast('无法读取剪贴板', 'error');
  }
}

function extractPhone(text) {
  const match = text.match(/1[3-9]\\d{9}|0\\d{2,3}[-\\s]?\\d{7,8}|\\d{7,11}/);
  return match ? match[0].replace(/[-\\s]/g, '') : null;
}

window.addEventListener('focus', async () => {
  try {
    const text = await navigator.clipboard.readText();
    const phone = extractPhone(text);
    const input = document.getElementById('numberInput');
    if (phone && !input.value) {
      input.value = phone;
      const hint = document.getElementById('clipboardHint');
      hint.classList.add('active');
      setTimeout(() => hint.classList.remove('active'), 800);
    }
  } catch (e) {}
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') dial();
  if (e.key === 'Backspace' && document.activeElement.id !== 'numberInput') deleteLast();
});

function addLog(type, msg) {
  const list = document.getElementById('logList');
  const now = new Date();
  const time = String(now.getHours()).padStart(2,'0') + ':' + String(now.getMinutes()).padStart(2,'0') + ':' + String(now.getSeconds()).padStart(2,'0');
  const item = document.createElement('div');
  item.className = 'log-item ' + type;
  item.innerHTML = '<div class="log-time">' + time + '</div><div class="log-msg">' + msg + '</div>';
  list.appendChild(item);
  list.scrollTop = list.scrollHeight;
  while (list.children.length > 100) list.removeChild(list.firstChild);
}

function clearLog() {
  document.getElementById('logList').innerHTML = '';
}

let toastTimer = null;
function showToast(msg, type) {
  type = type || 'info';
  const toast = document.getElementById('toast');
  toast.textContent = msg;
  toast.className = 'toast show ' + type;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toast.className = 'toast'; }, 2500);
}

loadInfo();
connect();
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
      exec('"' + edgePath + '" --app="' + url + '" --window-size=1100,750', (err) => {
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
        exec('"' + chromePath + '" --app="' + url + '" --window-size=1100,750', (err) => {
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
