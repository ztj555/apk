"""
AutoDial 云中转服务器 - Python 版（带 Web 管理界面）
功能：WebSocket 中转 + 系统托盘图标 + Web 可视化界面，打包为单个 EXE
依赖：websockets, pystray, Pillow
"""

import asyncio
import json
import logging
import sys
import os
import signal
import threading
import subprocess
from collections import defaultdict
from datetime import datetime, timedelta
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

import websockets
from websockets.legacy.server import serve

# ==================== 配置 ====================
DEFAULT_PORT = 35430
PORT = DEFAULT_PORT
WEB_PORT = 35431  # Web 管理界面端口

# 解析命令行参数
for i, arg in enumerate(sys.argv[1:]):
    if arg in ('--port', '-p') and i + 1 < len(sys.argv) - 1:
        try:
            PORT = int(sys.argv[i + 2])
        except ValueError:
            pass
    if arg in ('--web-port', '-w') and i + 1 < len(sys.argv) - 1:
        try:
            WEB_PORT = int(sys.argv[i + 2])
        except ValueError:
            pass

# ==================== 日志 ====================
log_file_path = None

def setup_logging():
    global log_file_path
    app_data = os.path.join(os.environ.get('APPDATA', os.path.expanduser('~')),
                            'autodial-cloud-relay')
    os.makedirs(app_data, exist_ok=True)
    log_file_path = os.path.join(app_data, 'cloud-relay.log')

    logger = logging.getLogger('relay')
    logger.setLevel(logging.INFO)

    # 文件日志
    fh = logging.FileHandler(log_file_path, encoding='utf-8')
    fh.setFormatter(logging.Formatter('[%(asctime)s] [%(levelname)s] %(message)s',
                                       datefmt='%Y-%m-%dT%H:%M:%S'))
    logger.addHandler(fh)

    # 控制台日志
    ch = logging.StreamHandler()
    ch.setFormatter(logging.Formatter('[%(asctime)s] [%(levelname)s] %(message)s',
                                       datefmt='%H:%M:%S'))
    logger.addHandler(ch)
    return logger

log = setup_logging()

# ==================== 统计数据结构 ====================
start_time = datetime.now()
total_messages = 0
total_bytes_sent = 0
total_bytes_received = 0
message_count_by_pin = defaultdict(int)  # pin -> 消息数
message_count_by_type = defaultdict(int)  # 消息类型 -> 计数
daily_stats = defaultdict(lambda: {'messages': 0, 'bytes': 0})  # YYYY-MM-DD -> stats

def record_message(pin, msg_type, bytes_count):
    """记录消息统计"""
    global total_messages, total_bytes_sent, total_bytes_received
    total_messages += 1
    if msg_type in ('dial', 'sms', 'hangup'):
        total_bytes_sent += bytes_count
    else:
        total_bytes_received += bytes_count
    message_count_by_pin[pin] += 1
    message_count_by_type[msg_type] += 1
    today = datetime.now().strftime('%Y-%m-%d')
    daily_stats[today]['messages'] += 1
    daily_stats[today]['bytes'] += bytes_count

# ==================== PIN 分组管理 ====================
class PinGroup:
    def __init__(self):
        self.pcs = set()      # websocket connections
        self.phones = set()   # websocket connections

# pin -> PinGroup
pin_groups: dict[str, PinGroup] = defaultdict(PinGroup)

# websocket -> metadata
ws_meta: dict = {}  # ws -> {pin, role, ip, device_name, connected_at, last_message_time}

def get_group(pin):
    if pin not in pin_groups:
        pin_groups[pin] = PinGroup()
    return pin_groups[pin]

def remove_from_group(ws):
    meta = ws_meta.get(ws)
    if not meta or not meta.get('pin'):
        return
    pin = meta['pin']
    group = pin_groups.get(pin)
    if not group:
        return
    group.pcs.discard(ws)
    group.phones.discard(ws)
    if not group.pcs and not group.phones:
        del pin_groups[pin]

# ==================== 心跳超时检测 ====================
HEARTBEAT_TIMEOUT = 90  # 90秒没收到消息就断开

async def check_heartbeats():
    """定期检查心跳超时，关闭超时的连接"""
    while True:
        await asyncio.sleep(30)  # 每30秒检查一次
        now = datetime.now()
        to_close = []
        
        for ws, meta in list(ws_meta.items()):
            last_time = meta.get('last_message_time')
            if last_time:
                elapsed = (now - last_time).total_seconds()
                if elapsed > HEARTBEAT_TIMEOUT:
                    to_close.append((ws, meta, elapsed))
        
        for ws, meta, elapsed in to_close:
            try:
                await ws.close(1000, f'Heartbeat timeout ({HEARTBEAT_TIMEOUT}s)')
                log.warning(f'HEARTBEAT_TIMEOUT {meta.get("role", "unknown")} pin={meta.get("pin", "none")} ip={meta.get("ip", "?")} elapsed={elapsed:.0f}s')
            except Exception:
                pass

# ==================== PIN 尝试频率限制 ====================
MAX_PIN_ATTEMPTS_PER_MINUTE = 5
_pin_attempts: dict[str, list] = defaultdict(list)

def check_rate_limit(client_ip: str) -> bool:
    """检查是否超频，返回 True 表示应该拒绝"""
    now = datetime.now()
    # 清理过期条目
    _pin_attempts[client_ip] = [
        t for t in _pin_attempts[client_ip] if now - t < timedelta(minutes=1)
    ]
    if len(_pin_attempts[client_ip]) >= MAX_PIN_ATTEMPTS_PER_MINUTE:
        return True
    _pin_attempts[client_ip].append(now)
    return False

# ==================== 消息转发 ====================
PHONE_TO_PC_TYPES = {
    'phone_hello', 'dial_result', 'sms_result', 'ping',
    # 上传协议（无状态透传）
    'file_upload_start', 'file_chunk', 'file_upload_complete', 'file_upload_error'
}
PC_TO_PHONE_TYPES = {
    'auth_ok', 'auth_fail', 'dial', 'sms', 'hangup',
    # 上传协议（无状态透传）
    'file_chunk_ack', 'file_upload_error'
}

async def forward_to_pcs(pin, message, exclude_ws=None):
    group = pin_groups.get(pin)
    if not group:
        return
    data = json.dumps(message, ensure_ascii=False)
    for pc in list(group.pcs):
        if pc != exclude_ws:
            try:
                await pc.send(data)
            except Exception:
                group.pcs.discard(pc)

async def forward_to_phones(pin, message, exclude_ws=None):
    group = pin_groups.get(pin)
    if not group:
        return
    data = json.dumps(message, ensure_ascii=False)
    target_device = message.get('targetDevice')
    for phone in list(group.phones):
        if phone != exclude_ws:
            # 如果指定了 targetDevice，只转发给匹配的设备
            if target_device:
                phone_meta = ws_meta.get(phone, {})
                phone_name = phone_meta.get('device_name', '')
                if phone_name != target_device:
                    continue
            try:
                await phone.send(data)
            except Exception:
                group.phones.discard(phone)

# ==================== WebSocket 处理 ====================
server_instance = None
ws_connections = set()

async def handle_connection(ws, path=None):
    client_ip = ws.remote_address[0] if ws.remote_address else 'unknown'
    ws_meta[ws] = {
        'pin': None,
        'role': None,
        'ip': client_ip,
        'device_name': None,
        'connected_at': datetime.now().isoformat(),
        'last_message_time': datetime.now()  # 添加最后消息时间用于心跳超时检测
    }
    ws_connections.add(ws)

    log.info(f'CONNECT {client_ip}')

    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
                msg_type = msg.get('type', '')
            except json.JSONDecodeError:
                continue

            # 更新最后消息时间（用于应用层心跳检测）
            if ws in ws_meta:
                ws_meta[ws]['last_message_time'] = datetime.now()

            meta = ws_meta.get(ws, {})

            # ===== 手机端握手 =====
            if msg_type == 'phone_hello':
                # 频率限制检查
                if check_rate_limit(client_ip):
                    await ws.send(json.dumps({'type': 'auth_fail', 'reason': '请求过于频繁，请稍后再试'}))
                    log.warning(f'RATE_LIMITED phone_hello ip={client_ip}')
                    continue
                pin = msg.get('pin', '')
                if not pin or len(pin) < 4:
                    await ws.send(json.dumps({'type': 'auth_fail', 'reason': '配对码无效'}))
                    continue
                remove_from_group(ws)
                meta['pin'] = pin
                meta['role'] = 'phone'
                meta['device_name'] = msg.get('deviceName', f'Phone-{client_ip[-3:]}')
                group = get_group(pin)
                group.phones.add(ws)
                # 回复手机端认证成功（告知当前在线 PC 数）
                await ws.send(json.dumps({
                    'type': 'auth_ok',
                    'pin': pin,
                    'pcCount': len(group.pcs)
                }))
                # 转发 phone_hello 给同 PIN 的所有 PC
                await forward_to_pcs(pin, msg, ws)
                log.info(f'PHONE_HELLO pin={pin} device={meta["device_name"]} ip={client_ip} pcs={len(group.pcs)}')
                continue

            # ===== PC 端握手 =====
            if msg_type == 'pc_hello':
                # 频率限制检查
                if check_rate_limit(client_ip):
                    await ws.send(json.dumps({'type': 'pc_auth_fail', 'reason': '请求过于频繁，请稍后再试'}))
                    log.warning(f'RATE_LIMITED pc_hello ip={client_ip}')
                    continue
                pin = msg.get('pin', '')
                if not pin or len(pin) < 4:
                    await ws.send(json.dumps({'type': 'pc_auth_fail', 'reason': '配对码无效'}))
                    continue
                remove_from_group(ws)
                meta['pin'] = pin
                meta['role'] = 'pc'
                meta['device_name'] = msg.get('hostname', f'PC-{client_ip[-3:]}')
                group = get_group(pin)
                group.pcs.add(ws)
                await ws.send(json.dumps({
                    'type': 'pc_auth_ok',
                    'pin': pin,
                    'phoneCount': len(group.phones)
                }))
                log.info(f'PC_HELLO pin={pin} hostname={meta["device_name"]} ip={client_ip} phones={len(group.phones)}')
                continue

            # ===== 未握手则拒绝 =====
            if not meta.get('pin'):
                await ws.send(json.dumps({'type': 'error', 'reason': '请先发送 phone_hello 或 pc_hello'}))
                continue

            pin = meta['pin']

            # ===== 手机→PC 转发 =====
            if msg_type in PHONE_TO_PC_TYPES:
                await forward_to_pcs(pin, msg, ws)
                if msg_type == 'ping':
                    await ws.send(json.dumps({'type': 'pong'}))
                    # ping 不记日志，避免刷屏
                else:
                    log.info(f'RELAY {msg_type} phone→pc pin={pin}')
                continue

            # ===== PC→手机 转发 =====
            if msg_type in PC_TO_PHONE_TYPES:
                await forward_to_phones(pin, msg, ws)
                log.info(f'RELAY {msg_type} pc→phone pin={pin}')
                continue

            log.info(f'UNKNOWN type={msg_type} pin={pin}')

    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as e:
        log.error(f'Connection error: {e}')
    finally:
        remove_from_group(ws)
        meta = ws_meta.pop(ws, {})
        ws_connections.discard(ws)
        log.info(f'DISCONNECT {meta.get("role", "unknown")} pin={meta.get("pin", "none")} ip={meta.get("ip", "?")}')

# ==================== 防火墙配置 ====================
def configure_firewall():
    """自动配置 Windows 防火墙规则（需要管理员权限）"""
    import subprocess
    
    rules = [
        (f'AutoDial Cloud Relay (WebSocket {PORT})', PORT),
        (f'AutoDial Cloud Relay (Web {WEB_PORT})', WEB_PORT)
    ]
    
    for rule_name, port in rules:
        # 先尝试删除已存在的规则（避免重复）
        try:
            subprocess.run([
                'netsh', 'advfirewall', 'firewall', 'delete', 'rule',
                f'name={rule_name}'
            ], capture_output=True, text=True, timeout=5)
        except Exception:
            pass
        
        # 添加入站规则
        try:
            result = subprocess.run([
                'netsh', 'advfirewall', 'firewall', 'add', 'rule',
                f'name={rule_name}',
                'dir=in',
                'action=allow',
                'protocol=TCP',
                f'localport={port}'
            ], capture_output=True, text=True, timeout=5)
            
            if result.returncode == 0:
                log.info(f'防火墙规则已添加: {rule_name} (端口 {port})')
            else:
                log.warning(f'添加防火墙规则失败: {rule_name} - {result.stderr}')
        except subprocess.TimeoutExpired:
            log.error(f'添加防火墙规则超时: {rule_name}')
        except Exception as e:
            log.error(f'添加防火墙规则错误: {rule_name} - {e}')
    
    log.info('防火墙配置完成（如果失败，请以管理员身份运行程序）')

# ==================== HTTP 健康检查 + Web 管理界面 ====================
HTML_CONTENT = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AutoDial 云中转 - 管理界面</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #f5f5f5; color: #333; }
        .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px; text-align: center; }
        .header h1 { font-size: 24px; margin-bottom: 5px; }
        .header p { opacity: 0.9; font-size: 14px; }
        .nav { background: white; padding: 10px; display: flex; gap: 10px; border-bottom: 1px solid #ddd; flex-wrap: wrap; }
        .nav-btn { padding: 8px 16px; border: none; background: #f0f0f0; cursor: pointer; border-radius: 4px; font-size: 14px; }
        .nav-btn.active { background: #667eea; color: white; }
        .container { max-width: 1200px; margin: 20px auto; padding: 0 20px; }
        .page { display: none; }
        .page.active { display: block; }
        .card { background: white; border-radius: 8px; padding: 20px; margin-bottom: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .card h2 { font-size: 18px; margin-bottom: 15px; color: #333; }
        .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; margin-bottom: 20px; }
        .stat-card { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px; border-radius: 8px; }
        .stat-card h3 { font-size: 14px; opacity: 0.9; margin-bottom: 10px; }
        .stat-card .value { font-size: 28px; font-weight: bold; }
        table { width: 100%; border-collapse: collapse; }
        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }
        th { background: #f5f5f5; font-weight: 600; }
        .badge { display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 12px; }
        .badge-pc { background: #e3f2fd; color: #1976d2; }
        .badge-phone { background: #f3e5f5; color: #7b1fa2; }
        .btn { padding: 6px 12px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
        .btn-danger { background: #f44336; color: white; }
        .btn-danger:hover { background: #d32f2f; }
        .refresh-btn { background: #4caf50; color: white; border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer; float: right; }
        .refresh-btn:hover { background: #45a049; }
        .log-container { background: #1e1e1e; color: #d4d4d4; padding: 15px; border-radius: 4px; font-family: monospace; max-height: 500px; overflow-y: auto; font-size: 13px; }
        .log-line { margin-bottom: 5px; }
        .empty-state { text-align: center; padding: 40px; color: #999; }
    </style>
</head>
<body>
    <div class="header">
        <h1>🚀 AutoDial 云中转服务器</h1>
        <p>版本 2.0.0 | <span id="port">-</span> | 运行时间: <span id="uptime">-</span></p>
    </div>

    <div class="nav">
        <button class="nav-btn active" onclick="showPage('dashboard')">📊 仪表盘</button>
        <button class="nav-btn" onclick="showPage('clients')">👥 客户端管理</button>
        <button class="nav-btn" onclick="showPage('stats')">📈 流量统计</button>
        <button class="nav-btn" onclick="showPage('logs')">📋 日志记录</button>
        <button class="refresh-btn" onclick="refreshAll()">🔄 刷新数据</button>
    </div>

    <div class="container">
        <!-- 仪表盘页面 -->
        <div id="page-dashboard" class="page active">
            <div class="stats-grid">
                <div class="stat-card">
                    <h3>当前连接数</h3>
                    <div class="value" id="stat-connections">0</div>
                </div>
                <div class="stat-card">
                    <h3>PIN 组数</h3>
                    <div class="value" id="stat-groups">0</div>
                </div>
                <div class="stat-card">
                    <h3>总消息数</h3>
                    <div class="value" id="stat-messages">0</div>
                </div>
                <div class="stat-card">
                    <h3>总流量</h3>
                    <div class="value" id="stat-bytes">0 MB</div>
                </div>
            </div>

            <div class="card">
                <h2>📋 最近连接的客户端</h2>
                <table>
                    <thead>
                        <tr>
                            <th>设备名称</th>
                            <th>角色</th>
                            <th>PIN 码</th>
                            <th>IP 地址</th>
                            <th>连接时间</th>
                        </tr>
                    </thead>
                    <tbody id="recent-clients">
                        <tr><td colspan="5" style="text-align: center; color: #999;">加载中...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>

        <!-- 客户端管理页面 -->
        <div id="page-clients" class="page">
            <div class="card">
                <h2>👥 所有客户端 (<span id="client-count">0</span>)</h2>
                <table>
                    <thead>
                        <tr>
                            <th>设备名称</th>
                            <th>角色</th>
                            <th>PIN 码</th>
                            <th>IP 地址</th>
                            <th>连接时间</th>
                        </tr>
                    </thead>
                    <tbody id="clients-list">
                        <tr><td colspan="5" style="text-align: center; color: #999;">加载中...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>

        <!-- 流量统计页面 -->
        <div id="page-stats" class="page">
            <div class="stats-grid">
                <div class="stat-card">
                    <h3>总消息数</h3>
                    <div class="value" id="stats-total-messages">0</div>
                </div>
                <div class="stat-card">
                    <h3>上传流量</h3>
                    <div class="value" id="stats-bytes-sent">0 MB</div>
                </div>
                <div class="stat-card">
                    <h3>下载流量</h3>
                    <div class="value" id="stats-bytes-received">0 MB</div>
                </div>
            </div>

            <div class="card">
                <h2>📈 按天统计（最近7天）</h2>
                <table>
                    <thead>
                        <tr>
                            <th>日期</th>
                            <th>消息数</th>
                            <th>流量</th>
                        </tr>
                    </thead>
                    <tbody id="daily-stats">
                        <tr><td colspan="3" style="text-align: center; color: #999;">加载中...</td></tr>
                    </tbody>
                </table>
            </div>
        </div>

        <!-- 日志记录页面 -->
        <div id="page-logs" class="page">
            <div class="card">
                <h2>📋 系统日志（最近100条）</h2>
                <button class="refresh-btn" onclick="loadLogs()" style="margin-bottom: 15px;">🔄 刷新日志</button>
                <div class="log-container" id="log-container">
                    <div class="empty-state">加载中...</div>
                </div>
            </div>
        </div>
    </div>

    <script>
        const API_BASE = '';

        function showPage(pageId) {
            document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
            document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
            document.getElementById('page-' + pageId).classList.add('active');
            document.querySelector(`.nav-btn[onclick="showPage('${pageId}')"]`).classList.add('active');
            if (pageId === 'dashboard') loadDashboard();
            if (pageId === 'clients') loadClients();
            if (pageId === 'stats') loadStats();
            if (pageId === 'logs') loadLogs();
        }

        async function apiCall(endpoint) {
            const res = await fetch(API_BASE + endpoint);
            return await res.json();
        }

        function formatBytes(bytes) {
            if (bytes === 0) return '0 B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        }

        function formatUptime(seconds) {
            const h = Math.floor(seconds / 3600);
            const m = Math.floor((seconds % 3600) / 60);
            const s = Math.floor(seconds % 60);
            return `${h}小时 ${m}分钟 ${s}秒`;
        }

        async function loadDashboard() {
            const data = await apiCall('/api/status');
            document.getElementById('port').textContent = data.port;
            document.getElementById('uptime').textContent = formatUptime(data.uptime_seconds);
            document.getElementById('stat-connections').textContent = data.total_connections;
            document.getElementById('stat-groups').textContent = data.total_groups;
            document.getElementById('stat-messages').textContent = data.total_messages;
            document.getElementById('stat-bytes').textContent = formatBytes(data.total_bytes_sent + data.total_bytes_received);
            
            const tbody = document.getElementById('recent-clients');
            if (data.clients.length === 0) {
                tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: #999;">暂无客户端连接</td></tr>';
            } else {
                tbody.innerHTML = data.clients.slice(0, 10).map(c => `
                    <tr>
                        <td>${c.device_name}</td>
                        <td><span class="badge badge-${c.role}">${c.role === 'pc' ? 'PC' : '手机'}</span></td>
                        <td>${c.pin}</td>
                        <td>${c.ip}</td>
                        <td>${new Date(c.connected_at).toLocaleString('zh-CN')}</td>
                    </tr>
                `).join('');
            }
        }

        async function loadClients() {
            const data = await apiCall('/api/clients');
            document.getElementById('client-count').textContent = data.clients.length;
            const tbody = document.getElementById('clients-list');
            if (data.clients.length === 0) {
                tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: #999;">暂无客户端连接</td></tr>';
            } else {
                tbody.innerHTML = data.clients.map(c => `
                    <tr>
                        <td>${c.device_name}</td>
                        <td><span class="badge badge-${c.role}">${c.role === 'pc' ? 'PC' : '手机'}</span></td>
                        <td>${c.pin}</td>
                        <td>${c.ip}</td>
                        <td>${new Date(c.connected_at).toLocaleString('zh-CN')}</td>
                    </tr>
                `).join('');
            }
        }

        async function loadStats() {
            const data = await apiCall('/api/stats');
            document.getElementById('stats-total-messages').textContent = data.total_messages;
            document.getElementById('stats-bytes-sent').textContent = formatBytes(data.total_bytes_sent);
            document.getElementById('stats-bytes-received').textContent = formatBytes(data.total_bytes_received);
            
            const tbody1 = document.getElementById('daily-stats');
            if (data.daily.length === 0) {
                tbody1.innerHTML = '<tr><td colspan="3" style="text-align: center; color: #999;">暂无数据</td></tr>';
            } else {
                tbody1.innerHTML = data.daily.map(d => `
                    <tr>
                        <td>${d.date}</td>
                        <td>${d.messages}</td>
                        <td>${formatBytes(d.bytes)}</td>
                    </tr>
                `).join('');
            }
        }

        async function loadLogs() {
            const data = await apiCall('/api/logs');
            const container = document.getElementById('log-container');
            if (data.logs.length === 0) {
                container.innerHTML = '<div class="empty-state">暂无日志记录</div>';
            } else {
                container.innerHTML = data.logs.map(line => `<div class="log-line">${line}</div>`).join('');
                container.scrollTop = container.scrollHeight;
            }
        }

        function refreshAll() {
            const activePage = document.querySelector('.page.active');
            if (activePage) {
                const pageId = activePage.id.replace('page-', '');
                showPage(pageId);
            }
        }

        // 页面加载时自动加载仪表盘
        loadDashboard();
        // 每30秒自动刷新当前页面
        setInterval(refreshAll, 30000);
    </script>
</body>
</html>"""

def get_clients_list():
    """获取所有客户端列表"""
    clients = []
    for ws, meta in ws_meta.items():
        if meta.get('pin'):
            clients.append({
                'device_name': meta.get('device_name', 'Unknown'),
                'role': meta.get('role', 'unknown'),
                'pin': meta.get('pin', ''),
                'ip': meta.get('ip', 'unknown'),
                'connected_at': meta.get('connected_at', '')
            })
    return clients

def get_uptime_seconds():
    """获取运行时间（秒）"""
    return int((datetime.now() - start_time).total_seconds())

def get_daily_stats():
    """获取按天统计数据"""
    result = []
    for date in sorted(daily_stats.keys(), reverse=True)[:7]:
        stats = daily_stats[date]
        result.append({
            'date': date,
            'messages': stats['messages'],
            'bytes': stats['bytes']
        })
    return result

def get_logs(n=100):
    """读取最近 n 条日志"""
    if not log_file_path or not os.path.exists(log_file_path):
        return []
    try:
        with open(log_file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
            return [line.strip() for line in lines[-n:]]
    except Exception:
        return []

# ==================== HTTP 请求处理 ====================
async def health_check_handler(path, request_headers):
    """处理 HTTP 请求（健康检查 + API + Web 界面）"""
    # 如果是 WebSocket 握手请求，不拦截，让 websockets 库处理
    if request_headers.get('Upgrade', '').lower() == 'websocket':
        return None
    
    parsed = urlparse(path)
    path = parsed.path
    
    # 健康检查（兼容旧版本）
    if path == '/health':
        body = json.dumps({
            'service': 'AutoDial Cloud Relay',
            'version': '2.0.0',
            'port': PORT,
            'web_port': WEB_PORT,
            'uptime_seconds': get_uptime_seconds(),
            'total_connections': len(ws_connections),
            'total_groups': len(pin_groups)
        }, ensure_ascii=False).encode('utf-8')
        return (200, [('Content-Type', 'application/json')], body)
    
    # API: 状态
    if path == '/api/status':
        body = json.dumps({
            'service': 'AutoDial Cloud Relay',
            'version': '2.0.0',
            'port': PORT,
            'web_port': WEB_PORT,
            'uptime_seconds': get_uptime_seconds(),
            'total_connections': len(ws_connections),
            'total_groups': len(pin_groups),
            'total_messages': total_messages,
            'total_bytes_sent': total_bytes_sent,
            'total_bytes_received': total_bytes_received
        }, ensure_ascii=False).encode('utf-8')
        return (200, [('Content-Type', 'application/json')], body)
    
    # API: 客户端列表
    if path == '/api/clients':
        body = json.dumps({
            'clients': get_clients_list()
        }, ensure_ascii=False).encode('utf-8')
        return (200, [('Content-Type', 'application/json')], body)
    
    # API: 统计数据
    if path == '/api/stats':
        body = json.dumps({
            'total_messages': total_messages,
            'total_bytes_sent': total_bytes_sent,
            'total_bytes_received': total_bytes_received,
            'daily': get_daily_stats()
        }, ensure_ascii=False).encode('utf-8')
        return (200, [('Content-Type', 'application/json')], body)
    
    # API: 日志
    if path == '/api/logs':
        body = json.dumps({
            'logs': get_logs(100)
        }, ensure_ascii=False).encode('utf-8')
        return (200, [('Content-Type', 'application/json')], body)
    
    # Web 管理界面
    if path == '/' or path == '/index.html':
        return (200, [('Content-Type', 'text/html; charset=utf-8')], HTML_CONTENT.encode('utf-8'))
    
    # 404
    return (404, [('Content-Type', 'text/plain')], b'Not Found')

# ==================== 服务器启停 ====================
async def run_server():
    global server_instance
    log.info(f'Starting server on port {PORT}...')
    
    # 自动配置防火墙规则
    configure_firewall()

    # 启动心跳检测任务
    asyncio.create_task(check_heartbeats())
    log.info(f'Heartbeat checker started (timeout={HEARTBEAT_TIMEOUT}s)')

    async with serve(handle_connection, '0.0.0.0', PORT,
                     process_request=health_check_handler,
                     ping_interval=30,
                     ping_timeout=90,  # 增加 ping 超时到 90 秒
                     close_timeout=10) as server:
        server_instance = server
        log.info(f'Server started on port {PORT}, PID={os.getpid()}')
        log.info(f'Web 管理界面: http://0.0.0.0:{WEB_PORT}')

        # 通知托盘状态更新
        update_tray_status(True)

        # 保持运行
        await asyncio.Future()  # 永不完成

async def stop_server():
    global server_instance
    if server_instance:
        log.info('Stopping server...')
        # 关闭所有连接
        for ws in list(ws_connections):
            try:
                await ws.close(1001, 'server shutting down')
            except Exception:
                pass
        server_instance.close()
        await server_instance.wait_closed()
        server_instance = None
        log.info('Server stopped')
        update_tray_status(False)

# ==================== 系统托盘 ====================
tray_icon = None
server_running = False
loop = None  # asyncio event loop

def create_tray_icon():
    """创建托盘图标（绿色圆点）"""
    from PIL import Image, ImageDraw

    # 32x32 绿色圆点图标
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse([4, 4, 28, 28], fill=(76, 175, 80, 255))  # 绿色
    return img

def create_tray_icon_stopped():
    """创建停止状态图标（灰色圆点）"""
    from PIL import Image, ImageDraw

    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse([4, 4, 28, 28], fill=(158, 158, 158, 255))  # 灰色
    return img

def update_tray_status(running):
    """更新托盘图标和菜单"""
    global server_running, tray_icon
    server_running = running
    if tray_icon:
        try:
            if running:
                tray_icon.icon = create_tray_icon()
                tray_icon.title = f'AutoDial 云中转\n运行中 | 端口 {PORT}'
            else:
                tray_icon.icon = create_tray_icon_stopped()
                tray_icon.title = f'AutoDial 云中转\n已停止 | 端口 {PORT}'
            tray_icon.menu = create_menu()
        except Exception as e:
            log.error(f'Update tray error: {e}')

def create_menu():
    """创建托盘菜单"""
    import pystray
    status_text = '● 运行中' if server_running else '○ 已停止'
    return pystray.Menu(
        pystray.MenuItem(f'AutoDial 云中转 - {status_text}', None, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem(f'端口: {PORT}', None, enabled=False),
        pystray.MenuItem(f'Web: http://127.0.0.1:{WEB_PORT}', None, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem('停止服务器' if server_running else '启动服务器',
                         toggle_server, default=True),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem('打开 Web 管理界面', open_web),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem('打开日志', open_log),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem('退出', quit_app),
    )

def toggle_server():
    """切换服务器启停"""
    global loop
    if server_running:
        if loop and loop.is_running():
            asyncio.run_coroutine_threadsafe(stop_server(), loop)
    else:
        if loop and loop.is_running():
            asyncio.run_coroutine_threadsafe(start_server_task(), loop)

async def start_server_task():
    """启动服务器任务"""
    asyncio.create_task(run_server())

def open_web():
    """打开 Web 管理界面"""
    import webbrowser
    webbrowser.open(f'http://127.0.0.1:{WEB_PORT}')

def open_log():
    """打开日志文件"""
    if log_file_path and os.path.exists(log_file_path):
        os.startfile(log_file_path)

def quit_app():
    """退出应用"""
    global loop
    if loop and loop.is_running():
        asyncio.run_coroutine_threadsafe(shutdown(), loop)
    else:
        sys.exit(0)

async def shutdown():
    """优雅关闭"""
    await stop_server()
    if tray_icon:
        tray_icon.stop()
    sys.exit(0)

def run_tray():
    """在主线程运行托盘图标"""
    global tray_icon
    import pystray

    tray_icon = pystray.Icon(
        'AutoDial Cloud Relay',
        icon=create_tray_icon_stopped(),
        title=f'AutoDial 云中转\n已停止 | 端口 {PORT}',
        menu=create_menu()
    )
    tray_icon.run()

def run_server_thread():
    """在线程中运行 asyncio 服务器"""
    global loop
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        loop.run_until_complete(run_server())
    except Exception as e:
        log.error(f'Server error: {e}')
        update_tray_status(False)

# ==================== 主入口 ====================
def main():
    print('')
    print('========================================')
    print('  AutoDial Cloud Relay Server')
    print('  版本: 2.0.0 (带 Web 管理界面)')
    print('========================================')
    print(f'  Port:     {PORT}')
    print(f'  Web Port: {WEB_PORT}')
    print(f'  PID:      {os.getpid()}')
    print('========================================')
    print('')
    print(f'  Web 管理界面: http://127.0.0.1:{WEB_PORT}')
    print('')

    # 启动服务器线程
    server_thread = threading.Thread(target=run_server_thread, daemon=True)
    server_thread.start()

    # 主线程运行托盘（pystray 要求主线程）
    run_tray()

if __name__ == '__main__':
    main()
