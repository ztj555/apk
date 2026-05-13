# AutoDial 项目全面分析报告

> 分析时间：2026-05-13  
> 代码路径：`C:\Users\EDY\WorkBuddy\20260422091705\AutoDial`  
> 项目名称：AutoDial（一键拨号系统）  
> 当前版本：versionCode 8 / versionName 3.1

---

## 一、项目概述

AutoDial 是一套专为**电销场景**设计的跨屏一键拨号系统。用户在杭州金鼎明阳从事电销工作，日常需要大量拨打客户电话，该系统的核心价值是：**在电脑端或浏览器中点击/粘贴一个手机号，即可自动触发手机完成拨号，无需手动操作手机**。

系统由 **4 个端** 组成，形成完整的拨号闭环：

| 端 | 目录 | 技术栈 | 角色 |
|---|---|---|---|
| **Android 端** | `android/` | Kotlin 原生 APP | 接收指令 → 执行拨号/发短信 → 回报结果 |
| **PC 端** | `pc-app/` | Electron + Node.js | 桌面 exe，WebSocket 服务端 + 拨号 UI |
| **浏览器扩展端** | `AutoDial-Extension/` | Chrome Extension (MV3) | CRM 网页上检测手机号 → 一键拨打 |
| **云端中继端** | `cloud-relay/` | Python (websockets) / Node.js | 跨网络 WebSocket 中转服务器 |

---

## 二、四端架构与通信流程

### 2.1 整体通信架构

```
                         ┌─────────────────────┐
                         │   云中继服务器         │
                         │  (Python/Node.js)     │
                         │  端口 35430 (WS)       │
                         │  端口 35431 (Web管理)  │
                         └───────┬─────┬─────────┘
                    ws://          │     │         ws://
              ┌───────────────────┘     └──────────────────┐
              │                                              │
    ┌─────────┴──────────┐                     ┌─────────────┴──────────┐
    │   PC 端 (Electron)  │                     │   Android 端 (Kotlin)   │
    │   端口 35432 (WS)    │◄──── ws:// ────►│   DialService           │
    │   端口 35433 (UDP)   │                     │   ConnectionManager    │
    └─────────┬──────────┘                     └────────────────────────┘
              │ http://127.0.0.1:35432
    ┌─────────┴──────────┐
    │ 浏览器扩展 (Chrome)  │
    │ content-script.js   │
    │ background.js       │
    └────────────────────┘
```

### 2.2 双通道连接机制

系统支持 **局域网直连** 和 **云端中继** 两条通道，优先使用局域网，失败自动降级到云端：

| 阶段 | 局域网通道 | 云端通道 |
|---|---|---|
| **发现** | UDP 广播发现（端口 35433），4位配对码验证 | 直接连接云服务器（支持多服务器遍历） |
| **连接** | WebSocket `ws://IP:35432` | WebSocket `ws://server:35430` |
| **认证** | `phone_hello` → `auth_ok/auth_fail` | `phone_hello` → 云端转发给 PC |
| **降级** | LAN 断开 → 自动 fallback 到 Cloud | Cloud 断开 → 指数退避重连（5s→60s） |
| **优先级** | **LAN 优先**，发送消息时自动选择 | Cloud 作为 LAN 不可用时的备份 |

### 2.3 配对码机制

- **PC 端**：基于本机 MAC 地址 + SHA256 生成 4 位数字配对码
- **Android 端**：用户输入配对码，UDP 广播匹配同一配对码的 PC
- **多设备隔离**：不同配对码的设备互不干扰
- **云端分组**：云中继服务器按 PIN 分组转发，同 PIN 的 PC 和手机才能通信

---

## 三、Android 端详解

### 3.1 整体架构

```
MainActivity (ViewPager2 + 3 Fragment)
├── ConnectFragment   → 连接/设置页
├── CallLogFragment   → 通话记录页
└── StatsFragment     → 统计页

DialService (Foreground Service)
├── ConnectionManager → 统一连接管理（LAN + Cloud）
├── CallLogDb         → SQLite 数据库
├── SimSelectOverlay  → 悬浮窗选卡
├── DialAnimationOverlay → 拨号动画
└── SmsConfirmActivity → 短信确认

BootReceiver → 开机自启
```

### 3.2 DialService — 核心后台服务

**职责**：常驻前台服务，维持 WebSocket 连接，接收 PC/云端指令执行拨号/挂断/发短信。

| 功能 | 实现方式 |
|---|---|
| 保活 | Foreground Service + PARTIAL_WAKE_LOCK（12h 自动释放） |
| 连接管理 | 委托给 `ConnectionManager`，支持 LAN + Cloud 双通道 |
| 拨号 | `TelecomManager.placeCall()` 指定 SIM 卡，fallback `ACTION_CALL` |
| 挂断 | `TelecomManager.endCall()`（需 Android 9+ ANSWER_PHONE_CALLS 权限） |
| 发短信 | 广播启动 `SmsConfirmActivity`，用户确认后 `SmsManager` 发送 |
| 通话监听 | `PhoneStateListener`，通话结束广播通知 UI 刷新 |
| 号码复制 | 拨号后自动复制号码到剪贴板（受开关控制） |
| 拨号动画 | `DialAnimationOverlay.show()`（烟花/弹跳/结合，受开关控制） |
| 通知栏 | 前台通知显示连接状态（"已连接到电脑(lan)" / "正在搜索电脑..." 等） |

### 3.3 ConnectionManager — 统一连接管理器

**状态机**：`DISCONNECTED → DISCOVERING → CONNECTING → CONNECTED`

| 方法 | 功能 |
|---|---|
| `connect(pin, hintIp)` | 单一入口：先 LAN 发现，失败 fallback Cloud |
| `connectCloudOnly(pin)` | 跳过 LAN，直接连云端 |
| `disconnect()` | 同时断开 LAN + Cloud |
| `disconnectCloud()` | 只断 Cloud，保留 LAN |
| `send(msg)` | 优先 LAN 发送，失败降级 Cloud |
| `loadSavedConfig()` | 从 SharedPreferences 恢复自动重连 |

**关键特性**：
- LAN 发现：3次 UDP 广播 + 5秒超时监听响应
- Cloud 重连：指数退避（5s → 10s → 20s → 40s → 60s）
- 应用层心跳：30秒 ping，保持连接活跃
- 双通道并存：transportMode 可为 `lan` / `cloud` / `lan+cloud`

### 3.4 DialMode — 拨号卡选择模式（7种）

| 模式 | Key | 说明 |
|---|---|---|
| 弹窗 | `popup` | 每次弹出自定义选卡界面 |
| 轮选 | `round_select` | 未识别→循环拨号；已识别→弹窗 |
| 相反 | `opposite` | 未识别→循环拨号；已识别→用另一张卡 |
| 卡1 | `sim1` | 始终使用卡1 |
| 卡2 | `sim2` | 始终使用卡2 |
| 循环 | `alternate` | 全局交替，查上一次拨号用的卡自动切换 |
| 记忆 | `remember` | 记住该号码上次用的卡；首次弹窗 |

### 3.5 SimSelectOverlay — 悬浮窗选卡

- 使用 `WindowManager.addView()` TYPE_APPLICATION_OVERLAY
- 解决 MIUI 后台拦截广播问题（不依赖 Activity 在前台）
- 30秒无操作自动消失
- UI 跟随 ThemeManager 主题色
- 显示"上次使用卡X 今天/昨天"提示

### 3.6 DialAnimationOverlay — 拨号成功动画

| 模式 | 效果 |
|---|---|
| MODE_FIREWORK (1) | 烟花绽放：文字从中心弹出 + 粒子火花四溅 |
| MODE_BOUNCE (2) | 弹性弹跳：文字从左侧弹性飞入 + 跳动 |
| MODE_COMBINE (3) | 结合：弹性飞入 + 烟花绽放 |

- 默认文字"财运+1"，可自定义
- Canvas 自绘，1.8秒后自动消失
- 全屏悬浮窗，可在任何界面显示

### 3.7 SmsConfirmActivity — 短信确认

- 透明全屏 Activity，代码动态构建 UI（无 XML 布局）
- 显示收件人 + 可编辑短信内容
- 支持长短信分段（`SmsManager.divideMessage`）
- 结果回报 PC：`{type:"sms_result", status:"sent"|"cancelled"|"error"}`

### 3.8 CallLogDb — 数据库

**数据库**：`autodial.db`（SQLiteOpenHelper, version 2）

| 表 | 字段 | 说明 |
|---|---|---|
| `dial_log` | _id, number, dial_time, sim_slot, status | APP 自身拨号记录 |
| `sim_cache` | number(PK), sim_slot, call_time | 系统通话记录同步缓存（最多500条） |

**关键查询方法**：
- `getLastSimSlotGlobal()` → 全局最近一次拨号用的卡（循环模式）
- `getLastSimSlot(number)` → 该号码上次用的卡（记忆模式）
- `getLastDialInfo(number)` → 该号码最近拨号的时间和卡（弹窗提示）
- `getDailyDurationStats(context, 7)` → 近7天通次+通时统计
- `syncFromSystemCallLog()` → 从系统 CallLog 同步 SIM 缓存

### 3.9 ThemeManager + ThemeDialog — 主题系统

- **16套主题** × **7级亮度** = 112种组合
- `ThemeColors` 数据类：bg, bg2, bg3, text, text2, gold, goldLight, goldDark, green, red
- `ThemeManager.applyToView(view, colors)` 递归遍历 View 树，按 `android:tag` 替换颜色
- `ThemeDialog`（BottomSheetDialog）：4列网格选主题 + 7级模式按钮
- 4个页面均已应用主题：ConnectFragment / CallLogFragment / StatsFragment / MainActivity

### 3.10 权限需求

| 权限 | 用途 |
|---|---|
| CALL_PHONE | 拨打电话 |
| READ_PHONE_STATE | 读取 SIM 卡信息 |
| READ_CALL_LOG | 读取系统通话记录 |
| SEND_SMS | 发送短信 |
| ANSWER_PHONE_CALLS | 挂断电话（Android 9+） |
| POST_NOTIFICATIONS | 前台服务通知（Android 13+） |
| READ_PHONE_NUMBERS | 读取手机号码 |
| SYSTEM_ALERT_WINDOW | 悬浮窗选卡 / 拨号动画 |

---

## 四、PC 端详解

### 4.1 整体架构

```
main.js (Electron 主进程)
├── HTTP/WebSocket 服务器 (端口 35432)
├── UDP 广播发现服务 (端口 35433)
├── 云中继连接管理
├── PhoneConnectionManager (phone-connection-manager.js)
├── 设置管理 (settings.json)
├── 日志系统 (200条环形缓冲)
└── 窗口管理
    ├── mainWindow (index.html)       → 主拨号界面
    ├── floatBarWindow (floatbar.html) → 悬浮横条
    ├── settingsWindow (settings.html) → 设置窗口
    └── smsWindow (sms.html)          → 短信窗口

preload.js → contextBridge IPC 桥接
```

### 4.2 主窗口（index.html）

- 无边框窗口，420×780
- 拨号盘 + 号码输入框
- 剪贴板 **500ms 轮询**自动读取手机号
- 拨打 / 挂断 / 发短信按钮
- 连接状态显示（IP + 配对码 + 状态圆点）
- 底部可折叠日志框（有新日志自动展开）
- 多手机设备列表 + 切换活跃设备 + 修改备注
- 云中转配置区（开关 + 服务器列表管理 + 测试连通性）

### 4.3 悬浮横条（floatbar.html）

- 独立原生 BrowserWindow，可拖到桌面任意位置
- 透明毛玻璃效果（`backdrop-filter: blur`）
- 内置：号码框 + 拨打 + 挂断 + 发短信按钮
- 支持缩放（0.7x ~ 1.5x，最小宽度 280px）
- 始终置顶（`alwaysOnTop: true`）
- 右键菜单：显示主窗口 / 隐藏悬浮条

### 4.4 短信窗口（sms.html）

- 收件人 + 多行文本框 + 字数统计
- 快捷模板管理（5条预设，localStorage 持久化）
- 发送状态 Toast 反馈

### 4.5 PhoneConnectionManager — 多手机设备管理

| 功能 | 实现 |
|---|---|
| 设备注册 | UUID = md5(deviceName:ip) LAN / md5(cloud:deviceName) Cloud |
| 最大连接数 | 5 台设备 |
| 活跃设备 | 自动选择 / 手动切换 |
| 消息路由 | LAN 优先 → Cloud 降级，`sendToPhone(uuid, msg)` |
| 心跳检测 | 90秒超时，定期 `checkHeartbeats()` |
| 文件上传 | 桩方法（`file_upload_start/chunk/complete`），预留未来实现 |

### 4.6 HTTP API（供浏览器扩展调用）

| 路径 | 方法 | 功能 |
|---|---|---|
| `/dial?number=xxx` | GET | HTTP 拨号接口 |
| `/hangup` | GET | HTTP 挂断接口 |
| `/sms?number=xxx&content=xxx` | GET | 打开短信窗口 |
| `/open` | GET | 打开主窗口 |
| `/toggle-floatbar` | GET | 切换悬浮条显示 |
| `/` (默认) | GET | 返回状态信息 JSON |

所有 HTTP 接口均加 CORS 头（`Access-Control-Allow-Origin: *`），允许浏览器插件跨域调用。

### 4.7 主题系统

- **16套主题** × **7级亮度**（与 Android 端一致）
- ThemeEngine（IIFE）+ CSS 变量注入
- IPC 广播同步多窗口主题（`theme-changed` 事件）
- 主题数据定义在 `themes/theme-data.js`，渲染端 `renderer/js/theme.js` 负责应用

### 4.8 设置持久化

存储在 `%APPDATA%/autodial-pc/settings.json`：

| 设置项 | 默认值 | 说明 |
|---|---|---|
| closeAction | minimize | 关闭按钮：最小化到托盘 |
| autoStart | false | 开机自启动（注册表方式） |
| silentStart | false | 隐藏界面启动 |
| theme | dark-gold | 主题ID |
| mode | dark | 亮度模式 |
| cloudEnabled | false | 云中转开关 |
| cloudServers | [] | 云服务器列表 |
| phoneNotes | {} | 手机备注 |

### 4.9 防火墙处理

启动时自动执行 `netsh advfirewall` 添加入站规则（TCP 35432 + UDP 35433），需管理员权限。失败时设置 `firewallWarning` 标志。

---

## 五、浏览器扩展端详解

### 5.1 技术架构

```
manifest.json (MV3)
├── background.js       → Service Worker，HTTP API 转发
├── content-script.js   → 内容脚本，CRM 网页手机号检测 + 悬浮按钮
├── popup.html/js       → 弹出面板
└── icons/              → 扩展图标
```

### 5.2 content-script.js — 核心功能

**双模式运行**：

| 模式 | 运行环境 | 功能 |
|---|---|---|
| 顶层页面 | `window === window.top` | 拨号悬浮按钮 + 挂断悬浮按钮 + 右键菜单 |
| 子 iframe | CRM 页面内嵌框架 | 扫描手机号 + 拦截"点击拨打"链接 |

**顶层页面功能**：
1. **拨号悬浮按钮**（`__ad_float`）
   - 可拖动（左右各18%为拖动区，中间为点击区）
   - 检测到手机号时显示号码，点击拨号
   - 拨号成功/失败有颜色闪烁反馈
   - 右键菜单：拨号/发短信/切换主题/打开PC端/切换悬浮窗

2. **挂断悬浮按钮**（`__ad_hangup`）
   - 椭圆形，可拖动
   - 左下角缩放手柄（36px ~ 100px）
   - 点击挂断，有闪烁反馈

3. **右键菜单**
   - 打开电脑端主界面
   - 显示/隐藏悬浮窗
   - 拨打号码 / 发短信
   - 切换主题（8套精选主题）
   - 获取当前位置（调试用）

**子 iframe 功能**：
1. **手机号扫描**：`TreeWalker` 遍历 DOM，找"手机号码："标签后的手机号
2. **拦截"点击拨打"链接**：拦截 CRM 原生拨打链接，改为通过 AutoDial 拨打
3. **`MutationObserver`**：监听 DOM 变化，自动重新扫描

**跨 frame 通信**：
- 子 iframe → `chrome.runtime.sendMessage({type: 'phoneDetected'})` → background
- background → `chrome.tabs.sendMessage(tabId, {type: 'updatePhone'})` → 顶层 content-script

### 5.3 background.js — Service Worker

| 消息类型 | 处理方式 |
|---|---|
| `phoneDetected` | 转发给顶层页面浮动按钮 |
| `dial` | HTTP GET `http://127.0.0.1:35432/dial?number=xxx` |
| `hangup` | HTTP GET `http://127.0.0.1:35432/hangup` |
| `openDesktop` | HTTP GET `http://127.0.0.1:35432/open` |
| `toggleFloatbar` | HTTP GET `http://127.0.0.1:35432/toggle-floatbar` |
| `sendSms` | HTTP GET `http://127.0.0.1:35432/sms?number=xxx` |

### 5.4 扩展主题系统

精选 8 套主题（暗金/冰蓝冷峻/深空紫/赛博朋克/极简白/森林绿/活力橙/海洋蓝），每套包含 accent/bg/text/gradient 完整色系。主题选择持久化到 `localStorage`。

### 5.5 CRM 兼容

- `host_permissions` 声明 `*://guwen.zhudaicms.com/*`
- content-script 注入该域名，自动检测客户详情页手机号
- 子 iframe 扫描也支持（`all_frames: true`）

---

## 六、云端中继端详解

### 6.1 双版本实现

| 版本 | 文件 | 技术 | 功能 |
|---|---|---|---|
| 精简版 | `server.js` | Node.js (ws) | 基础 WebSocket 中转 + HTTP 健康检查 |
| 完整版 | `python/cloud_relay_v2.py` | Python (websockets) | 中转 + Web 管理界面 + 系统托盘 + 防火墙配置 |

### 6.2 Node.js 版（server.js）

- PIN 分组管理：`Map<pin, { pcs: Set<ws>, phones: Set<ws> }>`
- 消息转发：手机→PC / PC→手机，按 PIN 分组
- 心跳：60秒无消息断开（close code 4000）
- HTTP 健康检查：返回服务信息 JSON

### 6.3 Python 版（cloud_relay_v2.py）

**核心功能**：
1. **WebSocket 中转**（端口 35430）
   - 按 PIN 分组，同 PIN 的 PC 和手机才能互相通信
   - 手机→PC：`phone_hello`, `dial_result`, `sms_result`, `ping` + 上传协议
   - PC→手机：`auth_ok`, `auth_fail`, `dial`, `sms`, `hangup` + 上传协议
   - 支持 `targetDevice` 精确路由（多手机场景）

2. **Web 管理界面**（端口 35431）
   - 仪表盘：连接数 / PIN 组数 / 消息数 / 流量
   - 客户端管理：设备名称 / 角色 / PIN / IP / 连接时间
   - 流量统计：按天统计消息数和流量
   - 日志记录：最近100条系统日志

3. **安全特性**
   - PIN 尝试频率限制（5次/分钟/IP）
   - 心跳超时检测（90秒）
   - 防火墙自动配置

4. **系统托盘**
   - 绿色/灰色圆点图标表示运行/停止状态
   - 右键菜单：启停服务器 / 打开 Web 界面 / 打开日志 / 退出
   - 使用 pystray + Pillow

5. **打包**
   - PyInstaller 打包为单个 EXE
   - `Launcher.cs` / `launcher.cpp`：C++/C# 启动器

### 6.4 云端连接流程

```
1. PC 端发送 pc_hello {pin, hostname}
   → 云端回复 pc_auth_ok {pin, phoneCount}
   
2. 手机发送 phone_hello {pin, deviceName}
   → 云端转发 phone_hello 给同 PIN 的所有 PC
   → 云端回复 auth_ok {pin, pcCount}
   
3. 消息转发：
   手机 dial_result → 云端 → 同 PIN 的所有 PC
   PC dial/sms/hangup → 云端 → 同 PIN 的匹配手机（支持 targetDevice）
```

---

## 七、消息协议

### 7.1 WebSocket 消息类型

| 方向 | 消息类型 | 内容 | 说明 |
|---|---|---|---|
| 手机 → PC | `phone_hello` | `{pin, deviceName}` | 手机连接握手 |
| PC → 手机 | `auth_ok` | `{message, deviceId}` | 认证成功 |
| PC → 手机 | `auth_fail` | `{reason}` | 认证失败 |
| PC → 手机 | `kicked` | — | 被踢下线（同名设备替换） |
| PC → 手机 | `dial` | `{type:"dial", number}` | 触发拨号 |
| PC → 手机 | `hangup` | `{type:"hangup"}` | 挂断电话 |
| PC → 手机 | `sms` | `{type:"sms", number, content}` | 发送短信 |
| 手机 → PC | `dial_result` | `{type:"dial_result", number, status}` | 拨号结果 |
| 手机 → PC | `sms_result` | `{type:"sms_result", number, status}` | 短信结果 |
| 手机 → PC | `call_state` | `{type:"call_state", state}` | 通话状态变化 |
| 双向 | `ping` / `pong` | — | 心跳 |

### 7.2 云中转额外消息

| 方向 | 消息类型 | 说明 |
|---|---|---|
| PC → 云端 | `pc_hello` | PC 端握手 |
| 云端 → PC | `pc_auth_ok` / `pc_auth_fail` | PC 认证结果 |
| 云端 → PC | `phone_hello`（转发） | 手机通过云端连接的通知 |
| PC → 云端 | `auth_ok`（含 targetDevice） | 指定目标手机的认证回复 |

### 7.3 HTTP API 消息（浏览器扩展用）

| 路径 | 参数 | 返回 |
|---|---|---|
| `/dial?number=xxx` | 手机号 | `{success, number}` 或 `{success, error}` |
| `/hangup` | 无 | `{success}` 或 `{success, error}` |
| `/sms?number=xxx&content=xxx` | 手机号+内容 | `{success, number}` |
| `/open` | 无 | `{success}` |
| `/toggle-floatbar?show=true/false` | 可选 | `{success, visible}` |

---

## 八、端口一览

| 端口 | 协议 | 用途 |
|---|---|---|
| 35430 | WebSocket | 云中继服务器 |
| 35431 | HTTP | 云中继 Web 管理界面 |
| 35432 | WebSocket + HTTP | PC 端主服务（手机直连 + 浏览器扩展调用） |
| 35433 | UDP | 局域网设备发现广播 |

---

## 九、项目版本演进

| 版本 | 时间 | 主要变更 |
|---|---|---|
| v1.0 | 2026-04-22 | 服务器中转模式（Node.js 服务器 + 网页端） |
| **v2.0** | 2026-04-23 | **局域网直连**，PC 改为 Electron exe，移除云服务器依赖 |
| v2.1 | 2026-04-23 | UDP 广播自动发现，移除 IP 手动输入 |
| v3.0 | 2026-04-24 | Electron 彻底重构，独立原生悬浮横条 |
| pc-v1.0 | 2026-04-25 | PC 端里程碑版本 |
| android-v1.1 | 2026-04-25 | ViewPager2 三页面架构 + SQLite + 通话记录/统计 |
| android-v1.2 | 2026-04-26 | 五种拨号卡选择模式 |
| android-v1.3 | 2026-04-26 | SimSelectOverlay 悬浮窗（解决 MIUI 后台限制） |
| android-v1.6 | 2026-04-26 | 轮选模式（ROUND_SELECT） |
| SMS 功能 | 2026-04-29 | PC→手机发短信 + SmsConfirmActivity + 快捷模板 |
| **v3.1** | 2026-04-28 | 16套主题×7级亮度 + PC端主题系统 |
| Android 主题 | 2026-07-14 | ThemeManager + ThemeDialog + 4页面主题适配 |
| 云中继 | 后续 | Python 云中继 + Web 管理界面 + 浏览器扩展 |
| 浏览器扩展 v3.0 | 后续 | content-script 双模式 + 8套主题 + 挂断按钮 |
| 相反模式 | 后续 | OPPOSITE 拨号模式（7种选卡模式） |

---

## 十、签名与构建

### 10.1 Android 签名

- **签名文件**：`autodial-release.p12`（PKCS12, RSA 2048 + SHA256, 25年有效期）
- **GitHub Secrets**：`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
- 签名更换后用户需卸载旧版才能安装新版

### 10.2 CI/CD（GitHub Actions）

- 触发条件：push 到 `master` 分支
- 自动解码 Keystore（Base64 → `.p12`）
- 构建 Debug APK + Release APK（签名）
- 上传 Artifact 供下载

### 10.3 PC 端打包

- 使用 `@electron/packager`（非 electron-builder，因 winCodeSign 符号链接问题）
- 打包脚本：`pack.js`
- 构建产物：`output/` 目录

### 10.4 云中继打包

- PyInstaller 打包为单个 EXE
- `Launcher.cs` / `launcher.cpp`：辅助启动器

---

## 十一、已知问题与技术注意点

### 11.1 平台兼容性

| 问题 | 影响 | 解决方案 |
|---|---|---|
| MIUI 省电策略杀后台 | WebSocket 断连 | PARTIAL_WAKE_LOCK + 引导电池白名单 |
| MIUI 后台拦截广播 | SimSelectOverlay 无法弹出 | 改用 WindowManager 悬浮窗直调 |
| Android 13+ 前台服务 | 通知权限 | 动态申请 POST_NOTIFICATIONS |
| TelecomManager vs ACTION_CALL | MIUI 安全策略阻止后台拨号 | 优先 TelecomManager.placeCall() |
| electron-builder 签名失败 | Windows 普通权限 | 改用 @electron/packager |

### 11.2 代码注意点

1. **contextBridge**：`window.api` 不能用 `const xxx = window.xxx`（会报 `SyntaxError: Identifier already declared`），必须用 `window.xxx` 直接访问
2. **悬浮条窗口**：必须 `resizable: true`，否则 `setSize` 无法缩小窗口
3. **剪贴板轮询**：PC 端 500ms 轮询 clipboard，可能影响性能
4. **通话记录刷新**：多段延迟刷新（1s/3s/5s）+ ContentObserver + 30s 轮询兜底
5. **数据指纹去重**：CallLogFragment 使用 `buildFingerprint()` 避免无变化刷新 UI
6. **云端遍历代数**：`_cloudTraversalGeneration` 计数器防止新旧遍历冲突

### 11.3 未完成功能

- 文件上传协议：PC 端和 Android 端均只有桩方法（`sendFile` / `onFileUploadStart` 等）
- exe 体积较大（整目录 ~169MB）
- 缺少 `icon.ico` 图标（构建 warning）

---

## 十二、项目文件结构

```
AutoDial/
├── android/                              # Android 端
│   └── app/src/main/
│       ├── java/com/autodial/app/
│       │   ├── MainActivity.kt            # 主 Activity (ViewPager2 + 3 Fragment)
│       │   ├── DialService.kt             # 核心后台服务 (WebSocket+拨号+挂断+短信)
│       │   ├── ConnectionManager.kt       # 统一连接管理器 (LAN+Cloud双通道)
│       │   ├── ConnectFragment.kt         # 连接/设置页 (配对码+云中转+电池+主题)
│       │   ├── CallLogFragment.kt         # 通话记录页 (系统CallLog+选卡模式)
│       │   ├── StatsFragment.kt           # 统计页 (通次+通时+财运+柱状图)
│       │   ├── CallLogDb.kt               # SQLite 数据库 (dial_log+sim_cache)
│       │   ├── DialMode.kt                # 拨号卡选择模式枚举 (7种)
│       │   ├── SimSelectOverlay.kt        # 悬浮窗选卡 (WindowManager)
│       │   ├── DialAnimationOverlay.kt    # 拨号成功动画 (烟花/弹跳/结合)
│       │   ├── SmsConfirmActivity.kt      # 短信确认页 (透明Activity)
│       │   ├── ThemeManager.kt            # 主题管理器 (16主题×7亮度)
│       │   ├── ThemeDialog.kt             # 主题选择弹窗 (BottomSheetDialog)
│       │   ├── ViewPagerAdapter.kt        # ViewPager2 适配器
│       │   └── BootReceiver.kt            # 开机自启广播
│       ├── res/layout/                    # XML 布局
│       ├── res/drawable/                  # 图形资源
│       ├── res/values/                    # 颜色/字符串/主题
│       └── AndroidManifest.xml
├── pc-app/                               # PC 端 (Electron)
│   ├── main.js                           # Electron 主进程 (HTTP+WS+UDP+云中继)
│   ├── phone-connection-manager.js       # 多手机设备管理 (LAN+Cloud路由)
│   ├── preload.js                        # contextBridge IPC 桥接
│   ├── themes/theme-data.js              # 主题数据定义
│   ├── renderer/
│   │   ├── index.html                    # 主拨号界面
│   │   ├── floatbar.html                 # 悬浮横条
│   │   ├── sms.html                      # 短信窗口
│   │   ├── settings.html                 # 设置界面
│   │   └── js/theme.js                   # 主题引擎
│   ├── pack.js                           # 打包脚本
│   └── package.json                      # 依赖配置
├── AutoDial-Extension/                   # 浏览器扩展端
│   ├── manifest.json                     # MV3 扩展清单
│   ├── background.js                     # Service Worker (HTTP API 转发)
│   ├── content-script.js                 # 内容脚本 (手机号检测+悬浮按钮)
│   ├── popup.html / popup.js             # 弹出面板
│   ├── icons/                            # 扩展图标
│   ├── AutoDial-API.md                   # API 文档
│   ├── CRM技术分析.md                     # CRM 系统技术分析
│   └── README.md                         # 扩展说明
├── cloud-relay/                          # 云端中继端
│   ├── server.js                         # Node.js 精简版中转
│   ├── python/
│   │   ├── cloud_relay_v2.py             # Python 完整版 (中转+Web+托盘+防火墙)
│   │   ├── cloud_relay.py                # Python v1 版
│   │   └── web_server.py                 # 独立 Web 管理界面
│   ├── Launcher.cs                       # C# 启动器
│   ├── launcher.cpp                      # C++ 启动器
│   └── package.json                      # Node.js 依赖
├── .github/workflows/build.yml           # CI/CD 配置
├── README.md                             # 项目说明
├── WEBSOCKET_ERROR_CODES.md              # WebSocket 错误码文档
├── browser-extension-spec.md             # 浏览器扩展规格文档
├── AutoDial-Cloud-Review.md              # 云中继评审文档
└── start.cmd                             # 一键启动脚本
```

---

## 十三、后续改进建议

基于对4端代码的深入分析，以下方向可能值得考虑：

### 高优先级
1. **文件上传协议实现**：两端只有桩方法，PC→手机/手机→PC 文件传输能力缺失
2. **接通率统计**：目前统计只有通次/通时，缺少接通率（已接通/总拨号）
3. **自动拨号模式**：从号码列表批量自动拨号，接通/未接通自动记录

### 中优先级
4. **PC exe 体积优化**：当前整目录 ~169MB，可通过 `--ignore` 排除未用依赖
5. **通话记录导出**：支持导出为 CSV/Excel 格式
6. **剪贴板轮询优化**：PC 端 500ms 轮询改为事件驱动（`clipboard-watch`）
7. **浏览器扩展 CRM 适配扩展**：目前只适配 `guwen.zhudaicms.com`，可扩展更多 CRM 系统
8. **云中继安全增强**：PIN 码强度验证、TLS 加密（wss://）、IP 白名单

### 低优先级
9. **iOS 支持**：目前仅支持 Android
10. **APK 体积优化**：开启 R8 代码混淆
11. **短信群发**：支持批量发送短信模板
12. **浏览器扩展同步主题**：扩展主题与 PC 端/Android 端主题联动

---

*报告由 AI 基于项目全部源代码逐文件深入分析生成。*
