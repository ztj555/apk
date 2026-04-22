/**
 * AutoDial 中转服务器
 * 
 * 功能：
 * 1. WebSocket 连接管理（手机端 + 电脑端）
 * 2. 6位数配对码绑定（一台电脑 ↔ 一台手机）
 * 3. 拨号指令路由
 * 4. 心跳保活 + 自动清理
 * 
 * 免费部署方案：Render.com / Railway.app / Fly.io
 */

const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');
const crypto = require('crypto');

const app = express();
app.use(cors());
app.use(express.json());

const server = http.createServer(app);
const wss = new WebSocket.Server({ server, path: '/ws' });

// ==================== 数据存储 ====================

// 活跃连接：key = 设备ID, value = { ws, type: 'phone'|'pc', pairCode }
const activeDevices = new Map();

// 配对关系：key = 配对码(6位), value = { phoneId, pcId }
const pairings = new Map();

// 设备ID → 配对码（反向索引）
const deviceToPair = new Map();

// 未配对等待池：key = 配对码, value = { phoneId | pcId }
const pendingPair = new Map();

// ==================== 工具函数 ====================

function generatePairCode() {
  let code;
  do {
    code = String(Math.floor(100000 + Math.random() * 900000));
  } while (pairings.has(code) || pendingPair.has(code));
  return code;
}

function generateDeviceId() {
  return 'D_' + crypto.randomBytes(4).toString('hex');
}

function getTimestamp() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false });
}

// 向客户端发送消息
function send(ws, data) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(data));
  }
}

// 获取配对信息
function getPairedDevice(deviceId) {
  const pairCode = deviceToPair.get(deviceId);
  if (!pairCode) return null;
  const pairing = pairings.get(pairCode);
  if (!pairing) return null;
  const pairedId = pairing.phoneId === deviceId ? pairing.pcId : pairing.phoneId;
  return { pairedId, pairCode };
}

// 广播配对状态
function notifyPairStatus(deviceId, status, extra = {}) {
  const device = activeDevices.get(deviceId);
  if (device) {
    send(device.ws, { type: 'pair_status', status, ...extra });
  }
}

// ==================== 消息处理 ====================

function handleMessage(deviceId, data) {
  const device = activeDevices.get(deviceId);
  if (!device) return;

  switch (data.type) {
    // ---------- 手机端：生成配对码 ----------
    case 'phone_request_pair': {
      // 如果已配对，先解绑
      const existingPair = deviceToPair.get(deviceId);
      if (existingPair) {
        const oldPairing = pairings.get(existingPair);
        if (oldPairing) {
          const otherId = oldPairing.phoneId === deviceId ? oldPairing.pcId : oldPairing.phoneId;
          deviceToPair.delete(otherId);
          notifyPairStatus(otherId, 'unpaired', { reason: '对方重新配对' });
        }
        pairings.delete(existingPair);
      }

      const pairCode = generatePairCode();
      pendingPair.set(pairCode, { phoneId: deviceId });

      send(device.ws, {
        type: 'pair_code_generated',
        pairCode
      });

      console.log(`[${getTimestamp()}] 📱 手机 ${deviceId} 生成配对码: ${pairCode}`);
      break;
    }

    // ---------- 电脑端：输入配对码进行配对 ----------
    case 'pc_request_pair': {
      const { pairCode } = data;
      if (!pairCode || pairCode.length !== 6) {
        send(device.ws, { type: 'error', message: '配对码格式错误，应为6位数字' });
        return;
      }

      const pending = pendingPair.get(pairCode);
      if (!pending) {
        send(device.ws, { type: 'pair_failed', message: '配对码无效或已过期，请让手机重新生成' });
        return;
      }

      // 如果电脑已配对，先解绑
      const existingPair = deviceToPair.get(deviceId);
      if (existingPair) {
        const oldPairing = pairings.get(existingPair);
        if (oldPairing) {
          const otherId = oldPairing.pcId === deviceId ? oldPairing.phoneId : oldPairing.pcId;
          deviceToPair.delete(otherId);
          notifyPairStatus(otherId, 'unpaired', { reason: '对方重新配对' });
        }
        pairings.delete(existingPair);
      }

      // 建立配对关系
      const phoneId = pending.phoneId;
      pairings.set(pairCode, { phoneId, pcId: deviceId });
      pendingPair.delete(pairCode);

      deviceToPair.set(phoneId, pairCode);
      deviceToPair.set(deviceId, pairCode);

      // 通知双方配对成功
      const phoneDevice = activeDevices.get(phoneId);
      if (phoneDevice) {
        send(phoneDevice.ws, {
          type: 'pair_success',
          pairCode,
          message: `已与电脑配对成功`
        });
      }

      send(device.ws, {
        type: 'pair_success',
        pairCode,
        message: `已与手机配对成功`
      });

      console.log(`[${getTimestamp()}] ✅ 配对成功: ${pairCode} | 手机=${phoneId} ↔ 电脑=${deviceId}`);
      break;
    }

    // ---------- 电脑端：发送拨号指令 ----------
    case 'dial': {
      const { number, simSlot } = data;
      if (!number || number.trim() === '') {
        send(device.ws, { type: 'dial_error', message: '号码不能为空' });
        return;
      }

      const paired = getPairedDevice(deviceId);
      if (!paired) {
        send(device.ws, { type: 'dial_error', message: '未配对手机，请先完成配对' });
        return;
      }

      const phoneDevice = activeDevices.get(paired.pairedId);
      if (!phoneDevice) {
        send(device.ws, { type: 'dial_error', message: '手机不在线，请确认APP正在运行' });
        notifyPairStatus(deviceId, 'phone_offline');
        return;
      }

      // 转发拨号指令到手机
      send(phoneDevice.ws, {
        type: 'dial_request',
        number: number.trim(),
        simSlot: simSlot || 1,
        fromDeviceId: deviceId,
        timestamp: Date.now()
      });

      // 通知电脑已发送
      send(device.ws, {
        type: 'dial_sent',
        number: number.trim(),
        simSlot: simSlot || 1
      });

      console.log(`[${getTimestamp()}] 📞 拨号: ${number.trim()} (SIM${simSlot || 1}) → 手机 ${paired.pairedId}`);
      break;
    }

    // ---------- 手机端：确认已拨号 ----------
    case 'dial_result': {
      const { success, number, message } = data;
      const paired = getPairedDevice(deviceId);
      if (paired) {
        const pcDevice = activeDevices.get(paired.pairedId);
        if (pcDevice) {
          send(pcDevice.ws, {
            type: 'dial_result',
            success,
            number,
            message
          });
        }
      }
      break;
    }

    // ---------- 获取当前配对状态 ----------
    case 'get_status': {
      const pairCode = deviceToPair.get(deviceId);
      let status = 'unpaired';
      let pairedDeviceOnline = false;

      if (pairCode) {
        const pairing = pairings.get(pairCode);
        if (pairing) {
          status = 'paired';
          const otherId = pairing.phoneId === deviceId ? pairing.pcId : pairing.phoneId;
          pairedDeviceOnline = activeDevices.has(otherId);
        }
      }

      send(device.ws, { type: 'status_update', status, pairCode, pairedDeviceOnline });
      break;
    }

    // ---------- 请求解绑 ----------
    case 'unpair': {
      const pairCode = deviceToPair.get(deviceId);
      if (pairCode) {
        const pairing = pairings.get(pairCode);
        if (pairing) {
          const otherId = pairing.phoneId === deviceId ? pairing.pcId : pairing.phoneId;
          deviceToPair.delete(otherId);
          notifyPairStatus(otherId, 'unpaired', { reason: '对方主动解绑' });
          if (activeDevices.has(otherId)) {
            send(activeDevices.get(otherId).ws, { type: 'unpaired', reason: '对方主动解绑' });
          }
        }
        pairings.delete(pairCode);
        deviceToPair.delete(deviceId);
      }

      send(device.ws, { type: 'unpair_success', message: '已解绑' });
      console.log(`[${getTimestamp()}] 🔓 ${device.type} ${deviceId} 主动解绑`);
      break;
    }

    default:
      send(device.ws, { type: 'error', message: `未知消息类型: ${data.type}` });
  }
}

// ==================== WebSocket 连接管理 ====================

wss.on('connection', (ws, req) => {
  const deviceId = generateDeviceId();
  const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress;

  // 发送设备ID
  send(ws, { type: 'connected', deviceId });

  // 等待注册
  ws.on('message', (raw) => {
    try {
      const data = JSON.parse(raw);

      // 注册消息
      if (data.type === 'register') {
        if (!data.deviceType || !['phone', 'pc'].includes(data.deviceType)) {
          send(ws, { type: 'error', message: 'deviceType 必须为 phone 或 pc' });
          return;
        }

        activeDevices.set(deviceId, {
          ws,
          type: data.deviceType,
          ip,
          connectedAt: Date.now(),
          lastHeartbeat: Date.now()
        });

        // 检查重连：如果之前有配对，恢复配对状态
        // （简化处理：需要重新配对，保证安全性）

        console.log(`[${getTimestamp()}] 🟢 ${data.deviceType === 'phone' ? '📱' : '💻'} ${data.deviceType} 上线: ${deviceId} (IP: ${ip})`);
        return;
      }

      // 已注册才处理其他消息
      if (!activeDevices.has(deviceId)) {
        send(ws, { type: 'error', message: '请先注册设备' });
        return;
      }

      handleMessage(deviceId, data);
    } catch (e) {
      send(ws, { type: 'error', message: '消息格式错误' });
    }
  });

  ws.on('close', () => {
    const device = activeDevices.get(deviceId);
    if (device) {
      activeDevices.delete(deviceId);

      // 通知配对设备离线
      const paired = getPairedDevice(deviceId);
      if (paired && activeDevices.has(paired.pairedId)) {
        notifyPairStatus(paired.pairedId, 'phone_offline', { reason: '设备断开连接' });
      }

      console.log(`[${getTimestamp()}] 🔴 ${device.type} ${deviceId} 离线`);
    }
  });

  ws.on('error', (err) => {
    console.error(`[${getTimestamp()}] ⚠️ 连接错误 ${deviceId}:`, err.message);
  });
});

// ==================== REST API（状态查询） ====================

app.get('/api/stats', (req, res) => {
  res.json({
    onlineDevices: activeDevices.size,
    activePairings: pairings.size,
    pendingPairs: pendingPair.size,
    devices: Array.from(activeDevices.entries()).map(([id, d]) => ({
      id,
      type: d.type,
      ip: d.ip,
      connectedAt: new Date(d.connectedAt).toISOString()
    }))
  });
});

// ==================== 心跳检测（30秒间隔） ====================

setInterval(() => {
  const now = Date.now();
  for (const [id, device] of activeDevices) {
    if (now - device.lastHeartbeat > 90000) { // 90秒无心跳则断开
      console.log(`[${getTimestamp()}] ⏰ 心跳超时断开: ${id}`);
      device.ws.terminate();
      activeDevices.delete(id);
    }
  }
}, 30000);

// ==================== 启动 ====================

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log('');
  console.log('╔══════════════════════════════════════╗');
  console.log('║     AutoDial 中转服务器已启动         ║');
  console.log('╠══════════════════════════════════════╣');
  console.log(`║  地址: ws://localhost:${PORT}/ws        ║`);
  console.log('╠══════════════════════════════════════╣');
  console.log('║  手机端 → 生成配对码 → 等待电脑连接   ║');
  console.log('║  电脑端 → 输入配对码 → 配对后可拨号   ║');
  console.log('╚══════════════════════════════════════╝');
  console.log('');
});

module.exports = { app, server };
