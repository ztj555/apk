"""
AutoDial 云中转服务器 - Web 管理界面（独立版）
运行在 35431 端口，调用云服务器 API 显示管理界面
"""
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import urllib.request
import urllib.error

# 云服务器地址
CLOUD_SERVER_URL = "http://127.0.0.1:35430"

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
        const API_BASE = '""" + CLOUD_SERVER_URL + """';

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


def run_web_server():
    """启动 Web 服务器"""
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(HTML_CONTENT.encode('utf-8'))

        def log_message(self, format, *args):
            pass  # 禁用默认日志

    server = HTTPServer(('0.0.0.0', 35431), Handler)
    print('=' * 50)
    print('  AutoDial Web 管理界面')
    print('=' * 50)
    print(f'  访问地址: http://127.0.0.1:35431')
    print(f'  API 源: {CLOUD_SERVER_URL}')
    print('=' * 50)
    server.serve_forever()


if __name__ == '__main__':
    run_web_server()
