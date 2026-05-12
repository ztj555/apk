'use strict';

/**
 * AutoDial 云中转服务器
 *
 * 功能：PC 和手机都通过 WebSocket 连接到此服务器，服务器按 PIN 分组转发消息。
 * - 手机发来的 phone_hello/dial_result/sms_result/ping → 转发给同 PIN 的 PC
 * - PC 发来的 auth_ok/auth_fail/dial/sms/hangup → 转发给同 PIN 的手机
 *
 * 使用方式：node server.js [--port 35430]
 */

const { WebSocketServer } = require('ws');
const http = require('http');
const crypto = require('crypto');

// ==================== 配置 ====================
const DEFAULT_PORT = 35430;
const args = process.argv.slice(2);
let PORT = DEFAULT_PORT;
for (let i = 0; i < args.length; i++) {
  if ((args[i] === '--port' || args[i] === '-p') && args[i + 1]) {
    PORT = parseInt(args[i + 1], 10);
    i++;
  }
}

// ==================== PIN 分组管理 ====================
// pinGroups: Map<pin, { pcs: Set<ws>, phones: Set<ws> }>
const pinGroups = new Map();

function getGroup(pin) {
  if (!pinGroups.has(pin)) {
    pinGroups.set(pin, { pcs: new Set(), phones: new Set() });
  }
  return pinGroups.get(pin);
}

function removeFromGroup(ws) {
  if (!ws._pin) return;
  const group = pinGroups.get(ws._pin);
  if (!group) return;
  group.pcs.delete(ws);
  group.phones.delete(ws);
  // 清理空组
  if (group.pcs.size === 0 && group.phones.size === 0) {
    pinGroups.delete(ws._pin);
  }
}

// ==================== 消息转发 ====================

// 手机→PC 的消息类型
const PHONE_TO_PC_TYPES = new Set(['phone_hello', 'dial_result', 'sms_result']);

// PC→手机 的消息类型
const PC_TO_PHONE_TYPES = new Set(['auth_ok', 'auth_fail', 'dial', 'sms', 'hangup']);

function forwardToPCs(pin, message, excludeWs) {
  const group = pinGroups.get(pin);
  if (!group) return;
  const data = typeof message === 'string' ? message : JSON.stringify(message);
  group.pcs.forEach(pc => {
    if (pc !== excludeWs && pc.readyState === 1) { // OPEN
      try { pc.send(data); } catch (e) {}
    }
  });
}

function forwardToPhones(pin, message, excludeWs) {
  const group = pinGroups.get(pin);
  if (!group) return;
  const data = typeof message === 'string' ? message : JSON.stringify(message);
  group.phones.forEach(phone => {
    if (phone !== excludeWs && phone.readyState === 1) { // OPEN
      try { phone.send(data); } catch (e) {}
    }
  });
}

// ==================== HTTP 健康检查 ====================
const httpServer = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({
    service: 'AutoDial Cloud Relay',
    version: '1.0.0',
    uptime: process.uptime(),
    groups: pinGroups.size,
    connections: wss.clients.size
  }));
});

// ==================== WebSocket 服务器 ====================
const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (ws, req) => {
  const clientIP = req.socket.remoteAddress.replace('::ffff:', '');
  ws._ip = clientIP;
  ws._pin = null;
  ws._role = null; // 'pc' | 'phone'
  ws._deviceName = null;
  ws._connectedAt = Date.now();

  log('CONNECT', `${clientIP} connected`);

  // ========== 心跳管理：任何消息都重置心跳定时器 ==========
  function resetHeartbeat() {
    clearTimeout(ws._heartbeatTimer);
    ws._heartbeatTimer = setTimeout(() => {
      if (ws.readyState === 1) {
        log('HEARTBEAT', `pin=${ws._pin || 'none'} role=${ws._role || 'unknown'} timeout, closing`);
        ws.close(4000, 'heartbeat timeout');
      }
    }, 60000);
  }
  resetHeartbeat();

  // 响应 WebSocket 协议层 ping/pong 帧
  ws.on('ping', () => { resetHeartbeat(); });
  ws.on('pong', () => { resetHeartbeat(); });

  ws.on('message', (raw) => {
    resetHeartbeat();  // ← 任何消息都重置心跳（包括非 JSON 消息）
    try {
      const msg = JSON.parse(raw);
      const type = msg.type;

      // ========== 处理 JSON ping（PC 和手机都会发）==========
      if (type === 'ping') {
        try { ws.send(JSON.stringify({ type: 'pong' })); } catch (e) {}
        return;
      }

      // ========== 手机端握手 ==========
      if (type === 'phone_hello') {
        const pin = msg.pin;
        if (!pin || pin.length < 4) {
          ws.send(JSON.stringify({ type: 'auth_fail', reason: '配对码无效' }));
          return;
        }
        // 先从旧组移除（如果重连）
        removeFromGroup(ws);
        // 加入新组
        ws._pin = pin;
        ws._role = 'phone';
        ws._deviceName = msg.deviceName || ('Phone-' + clientIP.slice(-3));
        const group = getGroup(pin);
        group.phones.add(ws);

        // 转发 phone_hello 给同 PIN 的所有 PC
        forwardToPCs(pin, msg, ws);
        log('PHONE_HELLO', `pin=${pin} device=${ws._deviceName} ip=${clientIP}`);
        return;
      }

      // ========== PC 端握手 ==========
      if (type === 'pc_hello') {
        const pin = msg.pin;
        if (!pin || pin.length < 4) {
          ws.send(JSON.stringify({ type: 'pc_auth_fail', reason: '配对码无效' }));
          return;
        }
        // 先从旧组移除
        removeFromGroup(ws);
        // 加入新组
        ws._pin = pin;
        ws._role = 'pc';
        ws._deviceName = msg.hostname || ('PC-' + clientIP.slice(-3));
        const group = getGroup(pin);
        group.pcs.add(ws);

        // 回复连接成功，告知当前在线手机数
        ws.send(JSON.stringify({
          type: 'pc_auth_ok',
          pin: pin,
          phoneCount: group.phones.size
        }));
        log('PC_HELLO', `pin=${pin} hostname=${ws._deviceName} ip=${clientIP} phones=${group.phones.size}`);
        return;
      }

      // ========== 未握手则拒绝 ==========
      if (!ws._pin) {
        ws.send(JSON.stringify({ type: 'error', reason: '请先发送 phone_hello 或 pc_hello' }));
        return;
      }

      // ========== 手机→PC 转发 ==========
      if (PHONE_TO_PC_TYPES.has(type)) {
        forwardToPCs(ws._pin, msg, ws);
        if (type === 'ping') {
          // 中转服务器也回复 pong（减少 PC 响应延迟）
          ws.send(JSON.stringify({ type: 'pong' }));
        }
        log('RELAY', `${type} phone→pc pin=${ws._pin}`);
        return;
      }

      // ========== PC→手机 转发 ==========
      if (PC_TO_PHONE_TYPES.has(type)) {
        forwardToPhones(ws._pin, msg, ws);
        log('RELAY', `${type} pc→phone pin=${ws._pin}`);
        return;
      }

      // ========== 未知消息类型 ==========
      log('UNKNOWN', `type=${type} pin=${ws._pin}`);

    } catch (e) {
      log('ERROR', `parse failed: ${e.message}`);
    }
  });

  ws.on('close', (code, reason) => {
    removeFromGroup(ws);
    log('DISCONNECT', `${ws._role || 'unknown'} pin=${ws._pin || 'none'} ip=${ws._ip} code=${code}`);
  });

  ws.on('error', (err) => {
    log('ERROR', `ws error: ${err.message}`);
  });

  // 心跳超时：60秒无消息则断开
  ws._heartbeatTimer = setTimeout(() => {
    if (ws.readyState === 1) {
      ws.close(4000, 'heartbeat timeout');
    }
  }, 60000);

  ws.on('ping', () => {
    clearTimeout(ws._heartbeatTimer);
    ws._heartbeatTimer = setTimeout(() => {
      if (ws.readyState === 1) ws.close(4000, 'heartbeat timeout');
    }, 60000);
  });
});

// ==================== 日志 ====================
function log(tag, message) {
  const now = new Date().toISOString();
  console.log(`[${now}] [${tag}] ${message}`);
}

// ==================== 启动 ====================
httpServer.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('========================================');
  console.log('  AutoDial Cloud Relay Server');
  console.log('========================================');
  console.log(`  Port:     ${PORT}`);
  console.log(`  PID:      ${process.pid}`);
  console.log('========================================');
  console.log('');
});

// 优雅退出
process.on('SIGINT', () => {
  log('SHUTDOWN', 'Received SIGINT, closing all connections...');
  wss.clients.forEach(ws => {
    try { ws.close(1001, 'server shutting down'); } catch (e) {}
  });
  httpServer.close(() => {
    log('SHUTDOWN', 'Server closed');
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 3000);
});

process.on('uncaughtException', (err) => {
  log('FATAL', err.message);
});
