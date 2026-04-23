'use strict';

const http = require('http');
const WebSocket = require('ws');
const os = require('os');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');
const crypto = require('crypto');

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
  // 取前8位16进制转成数字，再取4位
  const num = parseInt(hash.substring(0, 8), 16);
  return String(num % 9000 + 1000); // 保证是1000-9999
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
  // 优先返回192.168开头的
  const preferred = candidates.find(c => c.address.startsWith('192.168') || c.address.startsWith('10.') || c.address.startsWith('172.'));
  return preferred ? preferred.address : (candidates[0] ? candidates[0].address : '127.0.0.1');
}

const PIN_CODE = generatePinCode();
const LOCAL_IP = getLocalIP();
const PORT = 35432; // 固定端口，避免冲突

// ==================== 读取UI文件 ====================
function getUIPath(filename) {
  if (process.pkg) {
    // pkg 打包后，__dirname 指向 snapshot 虚拟文件系统
    const snapshotPath = path.join(__dirname, 'ui', filename);
    return snapshotPath;
  }
  return path.join(__dirname, 'ui', filename);
}

// ==================== WebSocket 服务器 ====================
let phoneSocket = null; // 当前连接的手机
let broadcastToUI = null; // 向UI推送状态的函数

const server = http.createServer((req, res) => {
  let filePath = req.url === '/' ? '/index.html' : req.url;
  
  // API 接口
  if (filePath === '/api/info') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({
      pin: PIN_CODE,
      ip: LOCAL_IP,
      port: PORT,
      connected: phoneSocket !== null,
      hostname: os.hostname()
    }));
    return;
  }

  // 静态文件
  const uiPath = getUIPath(filePath.replace(/^\//, ''));
  fs.readFile(uiPath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    const ext = path.extname(filePath);
    const mime = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' };
    res.writeHead(200, { 'Content-Type': (mime[ext] || 'text/plain') + '; charset=utf-8' });
    res.end(data);
  });
});

const wss = new WebSocket.Server({ server });

wss.on('connection', (ws, req) => {
  const clientIP = req.socket.remoteAddress.replace('::ffff:', '');
  console.log(`[连接] 新客户端: ${clientIP}`);

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data);
      
      // 手机端握手验证
      if (msg.type === 'phone_hello') {
        if (msg.pin !== PIN_CODE) {
          ws.send(JSON.stringify({ type: 'auth_fail', reason: '配对码错误' }));
          ws.close();
          console.log(`[拒绝] 配对码错误: ${msg.pin}`);
          return;
        }
        // 踢掉旧连接
        if (phoneSocket && phoneSocket.readyState === WebSocket.OPEN) {
          phoneSocket.send(JSON.stringify({ type: 'kicked', reason: '有新设备连接' }));
          phoneSocket.close();
        }
        phoneSocket = ws;
        ws.isPhone = true;
        ws.send(JSON.stringify({ type: 'auth_ok', message: '配对成功！' }));
        notifyUIStatus(true, clientIP);
        console.log(`[配对] 手机连接成功: ${clientIP}`);
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
          number: msg.number,
          sim: msg.sim || 1
        }));
        console.log(`[拨号] ${msg.number} (SIM${msg.sim})`);
        ws.send(JSON.stringify({ type: 'dial_sent', number: msg.number }));
        return;
      }

      // 手机回报拨号结果
      if (msg.type === 'dial_result') {
        console.log(`[结果] ${msg.number}: ${msg.status}`);
        notifyUIDialResult(msg);
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
      console.log(`[断开] 手机断开连接: ${clientIP}`);
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

// ==================== 启动服务器 ====================
server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`[错误] 端口 ${PORT} 已被占用，请关闭其他 AutoDial 实例`);
    // 用系统对话框提示（Windows）
    exec(`mshta vbscript:msgbox("AutoDial 端口被占用，请检查是否已有程序在运行！",48,"AutoDial 错误")(window.close)`);
    process.exit(1);
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('╔══════════════════════════════════════════╗');
  console.log('║         AutoDial PC 端已启动              ║');
  console.log('╠══════════════════════════════════════════╣');
  console.log(`║  本机IP:   ${LOCAL_IP.padEnd(30)}║`);
  console.log(`║  配对码:   ${PIN_CODE.padEnd(30)}║`);
  console.log(`║  端口:     ${String(PORT).padEnd(30)}║`);
  console.log('╠══════════════════════════════════════════╣');
  console.log('║  浏览器打开: http://localhost:' + PORT + '       ║');
  console.log('╚══════════════════════════════════════════╝');

  // 自动打开浏览器
  const url = `http://localhost:${PORT}`;
  if (process.platform === 'win32') {
    exec(`start ${url}`);
  } else if (process.platform === 'darwin') {
    exec(`open ${url}`);
  } else {
    exec(`xdg-open ${url}`);
  }
});

// 全局异常捕获，防止闪退
process.on('uncaughtException', (err) => {
  console.error('[未捕获异常]', err.message);
  console.error(err.stack);
});

process.on('unhandledRejection', (reason) => {
  console.error('[未处理Promise拒绝]', reason);
});
