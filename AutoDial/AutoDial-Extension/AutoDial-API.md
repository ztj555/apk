# AutoDial PC 端通信接口文档

> 版本：v2.0  
> 更新日期：2026-04-28  
> 适用场景：Edge 插件 / 第三方系统对接 PC 主程序

---

## 一、概览

AutoDial PC 端在本地监听两个端口：

| 端口 | 协议 | 用途 |
|------|------|------|
| **35432** | HTTP + WebSocket | 主服务（插件/第三方调用 + 手机端 WebSocket 长连） |
| **35433** | UDP 广播 | 手机端自动发现 PC 端（局域网） |

PC 端主程序（Electron）收到拨号指令后，通过 **WebSocket** 转发给已配对的 Android 手机端执行拨号。

---

## 二、HTTP 接口

### 2.1 CORS

所有 HTTP 响应都包含：

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type
```

浏览器插件、Web 页面可直接 `fetch` 调用，**无需担心跨域问题**。

---

### 2.2 GET `/` — 获取连接状态

**用途：** 检测 PC 端是否运行、手机是否已连接。

**请求：**
```
GET http://127.0.0.1:35432/
```

**响应（200）：**
```json
{
  "pin": "5678",
  "ip": "192.168.1.100",
  "port": 35432,
  "connected": true
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `pin` | string | 本机配对码（由 MAC 地址生成，每台机器固定） |
| `ip` | string | 本机局域网 IP |
| `port` | number | 服务端口（固定 35432） |
| `connected` | boolean | 手机是否已通过 WebSocket 配对连接 |

---

### 2.3 GET `/dial` — 触发拨号 ⭐ 核心接口

**用途：** 浏览器插件 / 第三方系统触发手机拨号。

**请求：**
```
GET http://127.0.0.1:35432/dial?number=13888888888
```

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `number` | string | ✅ | 待拨打的手机号，必须符合 `1[3-9]\d{9}` 格式 |

**成功响应（200）：**
```json
{
  "success": true,
  "number": "13888888888"
}
```

**失败响应（手机未连接）：**
```json
{
  "success": false,
  "error": "手机未连接"
}
```

**失败响应（号码格式错误）：**
```json
{
  "success": false,
  "error": "无效的手机号"
}
```

**PC 端收到请求后执行：**
1. 验证手机号格式
2. 通过 WebSocket 向 Android 发送 `{"type":"dial","number":"xxx"}` 指令
3. 调用 `clipboardy` 将号码写入 Windows 剪贴板
4. 返回 JSON 结果

---

## 三、WebSocket 接口

**地址：** `ws://127.0.0.1:35432`

WebSocket 是 Android 手机端（助贷通 App）的主要通信通道，浏览器插件也可通过此协议连接（可选）。

所有消息均为 JSON 字符串，通过 `ws.send(JSON.stringify({...}))` 传输。

---

### 3.1 Android 手机端 → PC 端

#### 握手（必须第一个发送）

```json
{
  "type": "phone_hello",
  "pin": "5678"
}
```

| 字段 | 说明 |
|------|------|
| `pin` | 配对码，与 PC 端显示的配对码一致才能建立连接 |

**PC 端响应 - 验证通过：**
```json
{
  "type": "auth_ok",
  "message": "配对成功！"
}
```

**PC 端响应 - 验证失败：**
```json
{
  "type": "auth_fail",
  "reason": "配对码错误"
}
```

---

#### 心跳保活（定期发送，建议每 20s 一次）

```json
{ "type": "ping" }
```

**PC 端响应：**
```json
{ "type": "pong" }
```

---

#### 拨号结果回报（Android 拨号后回传）

```json
{
  "type": "dial_result",
  "number": "13888888888",
  "status": "calling"
}
```

| `status` 值 | 含义 |
|-------------|------|
| `calling` | 正在拨号中 |
| `connected` | 通话已接通 |
| `ended` | 通话已结束 |
| `failed` | 拨号失败 |

---

### 3.2 PC 端 → Android 手机端

#### 拨号指令

```json
{
  "type": "dial",
  "number": "13888888888"
}
```

#### 挂断指令

```json
{ "type": "hangup" }
```

#### 踢下线通知（有新设备连接时）

```json
{
  "type": "kicked",
  "reason": "有新设备连接"
}
```

---

### 3.3 浏览器插件端（可选）→ PC 端

插件也可以通过 WebSocket 连接，功能与 HTTP `/dial` 接口等价（两种方式均可）。

#### 插件握手

```json
{
  "type": "plugin_hello"
}
```

**PC 端响应：**
```json
{
  "type": "plugin_ok",
  "message": "插件已连接",
  "phoneConnected": true
}
```

#### 插件发送拨号命令

```json
{
  "type": "dial",
  "number": "13888888888"
}
```

**PC 端响应 - 已转发：**
```json
{
  "type": "dial_sent",
  "number": "13888888888"
}
```

**PC 端响应 - 失败：**
```json
{
  "type": "dial_fail",
  "reason": "手机未连接"
}
```

---

## 四、UDP 广播发现（端口 35433）

手机 App 自动发现局域网中的 PC 端，无需手动输入 IP。

### 手机 → PC（广播搜索）

```json
{
  "type": "discover",
  "pin": "5678"
}
```

### PC → 手机（单播回复）

```json
{
  "type": "found",
  "pin": "5678",
  "ip": "192.168.1.100",
  "port": 35432
}
```

PC 端同时每 **3 秒**主动广播一次：

```json
{
  "type": "announce",
  "pin": "5678",
  "ip": "192.168.1.100",
  "port": 35432
}
```

---

## 五、典型调用流程

### 场景 A：CRM 插件点击"拨打"

```
Edge 插件（content-script）
  └→ chrome.runtime.sendMessage({type:'dial', phone:'13888888888'})
       └→ background.js
            └→ fetch('http://127.0.0.1:35432/dial?number=13888888888')
                 └→ PC 主程序（main.js）
                      ├→ clipboardy.write('13888888888')  // 写入剪贴板
                      └→ phoneSocket.send({type:'dial', number:'...'})  // 转发给手机
                           └→ Android 手机端执行拨号
```

### 场景 B：第三方系统直接调用

```javascript
const resp = await fetch('http://127.0.0.1:35432/dial?number=13888888888');
const data = await resp.json();
// data: { success: true, number: '13888888888' }
```

---

## 六、注意事项

1. **只限本机访问**：PC 端监听 `0.0.0.0:35432`，局域网内均可访问，请勿将此端口暴露到公网。

2. **手机必须先配对**：未配对时 `/dial` 返回 `{"success":false,"error":"手机未连接"}`。

3. **剪贴板写入由 PC 端负责**：浏览器插件的 Service Worker 中无法调用 `navigator.clipboard`，因此拨号时剪贴板写入由 PC 端主程序（`clipboardy`）执行。

4. **配对码固定不变**：由本机 MAC 地址的 SHA-256 哈希生成，范围 1000–9999，重启不会改变。

5. **CORS 已全局开放**：所有 HTTP 响应均包含 `Access-Control-Allow-Origin: *`，浏览器可直接调用。

---

## 七、快速测试

```powershell
# 检查 PC 端是否运行
curl http://127.0.0.1:35432/

# 触发拨号（手机需已连接）
curl "http://127.0.0.1:35432/dial?number=13888888888"
```
