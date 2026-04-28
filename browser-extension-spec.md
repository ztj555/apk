# AutoDial 浏览器插件开发 — 完整技术规格

> 本文档供另一个 AI 任务使用，包含开发 AutoDial 浏览器插件（作为电脑端 exe 的平替）所需的全部信息。
> 浏览器插件完全独立，不依赖现有的 Electron 电脑端程序。

---

## 一、项目背景

AutoDial 是电销场景的一键拨号系统：电脑发送号码指令 → 手机自动拨号。
现有架构是 **Electron 电脑端 exe + Android 原生 APP**，通过局域网 WebSocket 直连。

**新目标**：开发一个 **Chrome 浏览器插件**，替代 Electron exe 程序，功能等价但完全独立。
手机端 APP 不需要任何修改，插件直接与手机端通信。

---

## 二、网络架构（浏览器插件需要实现的部分）

```
┌──────────────────────┐    WebSocket     ┌──────────────────┐
│  Chrome 浏览器插件    │ ◄──────────────► │  Android 手机 APP │
│  (替代 Electron exe) │    局域网直连      │  (DialService.kt) │
└──────────────────────┘                  └──────────────────┘
```

**关键**：浏览器插件需要实现原本 Electron exe 承担的两个角色：
1. **WebSocket 服务端**（监听端口，让手机连接上来）—— 浏览器无法直接监听端口
2. **WebSocket 客户端**（连到手机，或者反过来让手机连到电脑）

**⚠️ 核心问题：浏览器插件无法监听 TCP 端口**（Chrome 扩展没有 socket server API）。
推荐解决方案：
- 方案 A：插件内嵌一个 **Native Messaging Host**（本地小 exe/node 脚本，负责 WebSocket 服务端）
- 方案 B：改用 **WebRTC** 或其他 P2P 方案（需要改动手机端，不推荐）
- 方案 C：使用一个 **超轻量本地中转**（手机和插件都作为客户端连到 localhost 的一个小服务）

> 建议：采用方案 A（Native Messaging），这是 Chrome 扩展官方推荐的与本地程序通信的方式。
> 或者更简单：直接打包一个 **便携版 Node.js 脚本**（~50行代码）作为 WebSocket 服务端，
> 浏览器插件通过 Native Messaging 或 HTTP 与之通信。

---

## 三、WebSocket 通信协议（完整）

### 3.1 端口

| 用途 | 端口 | 协议 |
|------|------|------|
| WebSocket 主通信 | **35432** | ws:// |
| UDP 局域网发现 | **35433** | UDP |

### 3.2 4 位配对码生成规则

电脑端生成，手机端输入配对。生成算法：

```javascript
function generatePinCode() {
  const mac = getMacAddress();  // 取本机第一个非内部网卡的 MAC 地址
  const hash = crypto.createHash('sha256').update(mac).digest('hex');
  const num = parseInt(hash.substring(0, 8), 16);
  return String(num % 9000 + 1000);  // 4位数字，范围 1000-9999
}
```

- PIN 在服务启动时一次性生成，运行期间不变
- 同一台电脑的 PIN 永远相同（基于 MAC 地址）
- 多人使用不同电脑时 PIN 不同，互不干扰

### 3.3 连接流程（手机连接到电脑）

```
1. 电脑端启动 WebSocket 服务端，监听 0.0.0.0:35432
2. 手机端 APP 连接 ws://<电脑IP>:35432
3. 手机端发送握手消息
4. 电脑端验证 PIN
5. 验证通过 → 手机端开始接收拨号指令
```

### 3.4 消息格式（全部 JSON）

#### 手机 → 电脑

**握手认证**（连接后第一条消息）：
```json
{ "type": "phone_hello", "pin": "1234" }
```

**心跳**（OkHttp 自动发送，30秒间隔）：
```json
{ "type": "ping" }
```

**拨号结果回报**：
```json
{ "type": "dial_result", "number": "13800138000", "status": "ok" }
// status 值: "ok" | "cancelled" | "error"
```

#### 电脑 → 手机

**认证成功**：
```json
{ "type": "auth_ok", "message": "配对成功！" }
```

**认证失败**：
```json
{ "type": "auth_fail", "reason": "配对码错误" }
```

**踢下线**（新手机连接时踢掉旧连接）：
```json
{ "type": "kicked", "reason": "有新设备连接" }
```

**拨号指令**（核心功能）：
```json
{ "type": "dial", "number": "13800138000" }
```

**挂断指令**：
```json
{ "type": "hangup" }
```

**心跳回复**（OkHttp 自动处理）：
```json
{ "type": "pong" }
```

### 3.5 电脑端 WebSocket 服务端逻辑

```
onConnection(ws):
  等待 phone_hello 消息
  验证 pin
    不匹配 → 发送 auth_fail → 关闭连接
    匹配 → 踢掉旧手机连接（如有） → 发送 auth_ok → 标记为已连接

  后续消息路由:
    dial_result → 更新 UI 状态
    ping → 回复 pong

  onClose:
    如果是手机连接 → 标记为未连接
```

**同一时间只允许一台手机连接**，新连接自动踢掉旧连接。

### 3.6 UDP 局域网发现（可选实现）

电脑端每 3 秒广播一次（端口 35433）：
```json
{ "type": "announce", "pin": "1234", "ip": "192.168.1.100", "port": 35432 }
```

手机端搜索时发送（广播到 255.255.255.255:35433）：
```json
{ "type": "discover", "pin": "1234" }
```

电脑端收到 discover 后回复：
```json
{ "type": "found", "pin": "1234", "ip": "192.168.1.100", "port": 35432 }
```

> 浏览器插件版本可以不实现 UDP 发现，让用户手动输入 IP。

---

## 四、手机端行为（不需要改动，但需要了解）

### 4.1 手机端连接成功后的行为

1. 发送 `phone_hello` 握手
2. 收到 `auth_ok` 后进入待命状态
3. 收到 `dial` 消息后：
   - 根据 APP 内设置的拨号模式（弹窗/轮选/卡1/卡2/循环/记忆）决定用哪张卡
   - 如果需要用户选卡 → 弹出悬浮窗让用户选
   - 拨号完成后发送 `dial_result`（status: ok/cancelled/error）
4. 收到 `hangup` → 挂断当前通话
5. 断线后 3 秒自动重连

### 4.2 手机端拨号模式

| 模式 | key | 行为 |
|------|-----|------|
| 弹窗 | popup | 每次都弹窗让用户选卡 |
| 轮选 | round_select | 已识别号码→弹窗；新号码→循环拨号 |
| 卡1 | sim1 | 固定用卡1 |
| 卡2 | sim2 | 固定用卡2 |
| 循环 | alternate | 全局交替：上次卡1→这次卡2，反之亦然 |
| 记忆 | remember | 同一号码用上次同一张卡，新号码弹窗 |

> **电脑端/插件端不需要管拨号模式**，手机端自己处理。电脑端只负责发送 `dial` 指令和接收 `dial_result`。

---

## 五、浏览器插件需要实现的功能

### 5.1 核心功能（必须有）

1. **WebSocket 服务端**（通过 Native Messaging 或本地小脚本）
   - 监听 0.0.0.0:35432
   - 实现完整的认证流程（phone_hello → auth_ok/auth_fail）
   - 踢掉旧连接机制
   - 转发拨号指令（dial）和挂断指令（hangup）
   - 接收拨号结果（dial_result）

2. **UI 面板**（popup 或 sidebar）
   - 显示本机 IP 和 4 位配对码
   - 显示手机连接状态（已连接/未连接 + 手机 IP）
   - 号码输入框 + 拨号盘
   - 拨号按钮 + 挂断按钮
   - 拨号结果显示（成功/失败/取消）

3. **剪贴板监听**
   - 轮询剪贴板（500ms~1000ms）
   - 自动提取手机号（正则：`1[3-9]\d{9}` 或 `0\d{2,3}[-\s]?\d{7,8}`）
   - 自动填入号码输入框

### 5.2 可选功能

4. **键盘快捷键**（Enter 拨号）
5. **拨号日志**（带时间戳）
6. **连接成功提示音/震动效果**
7. **防火墙提示**（端口可能被 Windows 防火墙拦截）
8. **窗口置顶功能**

---

## 六、现有 Electron 端的 UI 参考信息

### 6.1 UI 风格

- **暗金色主题**：深色背景 + 金色点缀
- 背景色：`#111318`（主）/ `#1A1D24`（卡片）/ `#22262F`（元素）
- 金色：`#C9A84C`（标准）/ `#F0C040`（亮）/ `#8B6914`（暗）
- 文字色：`#E8DCC8`（主）/ `#A09070`（副）
- 成功：`#2ECC71` / 失败：`#E74C3C`
- 圆角按钮、渐变色、按下态动画

### 6.2 布局参考

```
┌──────────────────────────────┐
│ AutoDial                  ─ □│  ← 标题栏（无边框窗口）
├──────────────────────────────┤
│ IP: 192.168.1.100  PIN: 1234│  ← 状态栏
│           ● 已连接            │
├──────────────────────────────┤
│  号码                         │
│  ┌──────────────────────┐   │
│  │ 138 0013 8000        │   │  ← 号码输入框（24sp粗体）
│  └──────────────────────┘   │
│                             │
│  ┌───┐ ┌───┐ ┌───┐         │
│  │ 1 │ │ 2 │ │ 3 │         │
│  └───┘ └───┘ └───┘         │
│  ┌───┐ ┌───┐ ┌───┐         │  ← 拨号盘 3x4
│  │ 4 │ │ 5 │ │ 6 │         │
│  └───┘ └───┘ └───┘         │
│  ┌───┐ ┌───┐ ┌───┐         │
│  │ 7 │ │ 8 │ │ 9 │         │
│  └───┘ └───┘ └───┘         │
│  ┌───┐ ┌───┐ ┌───┐         │
│  │ * │ │ 0 │ │ ⌫ │         │
│  └───┘ └───┘ └───┘         │
│                             │
│  [ 📞 拨号 ]  [ 📵 挂断 ]   │  ← 操作按钮
│                             │
│  ▼ 日志                      │
│  ┌──────────────────────┐   │
│  │ 10:30  ✓ 138... 已拨  │   │  ← 折叠日志区
│  └──────────────────────┘   │
└──────────────────────────────┘
```

### 6.3 交互细节

- 复制手机号后自动填入输入框（轮询剪贴板 500ms~1000ms）
- 按键有按压动画（scale 0.93）
- 连接成功显示绿色横幅 + Toast
- Enter 键直接拨号
- 日志自动展开新条目

---

## 七、技术限制与注意事项

### 7.1 浏览器扩展的限制

- **无法监听 TCP 端口**：Chrome 扩展 API 没有 socket server
  - 解决：Native Messaging Host（推荐）或单独的小型本地服务
- **剪贴板读取**：需要 `clipboardRead` 权限，但只能在扩展 popup/侧边栏获得焦点时读取
  - 解决：用 Document.execCommand('paste') 或 navigator.clipboard.readText()（需要用户授权）
- **保持连接**：Service Worker 可能在闲置时被挂起
  - 解决：用 offscreen document 或保持 popup 打开

### 7.2 推荐架构

```
Chrome Extension (popup/sidebar UI)
        │
        │ Native Messaging / HTTP localhost
        ▼
local-bridge.exe (Node.js 打包，~50行代码)
        │
        │ WebSocket Server (端口 35432)
        │ UDP Broadcast (端口 35433)
        ▼
Android APP (DialService.kt)
```

local-bridge 的职责：
1. 启动 WebSocket 服务端（ws 库，~20行）
2. 启动 UDP 广播（dgram 库，~10行）
3. 通过 Native Messaging 与 Chrome 扩展通信
4. 通过 stdin/stdout 传递 JSON 消息

### 7.3 防火墙问题

Windows 防火墙可能拦截 35432 和 35433 端口。现有 Electron 端的处理：
```javascript
// 自动添加防火墙入站规则（需要管理员权限）
exec('netsh advfirewall firewall add rule name="AutoDial" dir=in action=allow protocol=TCP localport=35432 profile=any');
```
浏览器插件版本建议：首次启动时检测端口是否可用，提示用户手动放行。

---

## 八、手机号提取正则

```javascript
function extractPhone(text) {
  const m = text.match(/1[3-9]\d{9}|0\d{2,3}[-\s]?\d{7,8}/);
  return m ? m[0].replace(/[-\s]/g, '') : null;
}
```

- 支持手机号：`13800138000`（11位，1开头）
- 支持座机号：`0571-88886666` 或 `057188886666`

---

## 九、现有项目信息

- **GitHub 仓库**：https://github.com/ztj555/apk
- **项目路径**：`C:\Users\EDY\WorkBuddy\20260422091705\AutoDial`
- **电脑端代码**：`pc-app/` 目录（Electron + Node.js）
- **手机端代码**：`android/` 目录（Kotlin）
- **手机端关键文件**：
  - `android/app/src/main/java/com/autodial/app/DialService.kt`（WebSocket 客户端 + 拨号逻辑）
  - `android/app/src/main/java/com/autodial/app/ConnectFragment.kt`（连接页面 + UDP 发现）
  - `android/app/src/main/java/com/autodial/app/CallLogDb.kt`（SQLite 数据库）
  - `android/app/src/main/java/com/autodial/app/SimSelectOverlay.kt`（悬浮窗选卡弹窗）

---

## 十、开发优先级建议

1. **P0（必须有）**：WebSocket 服务端 + 认证 + 拨号指令 + 拨号结果
2. **P0（必须有）**：UI 面板（IP/PIN 显示、连接状态、号码输入、拨号/挂断按钮）
3. **P1（应该有）**：剪贴板自动读取手机号
4. **P1（应该有）**：拨号盘（数字按钮）
5. **P2（锦上添花）**：UDP 局域网自动发现
6. **P2（锦上添花）**：拨号日志
7. **P2（锦上添花）**：暗金色 UI 主题
