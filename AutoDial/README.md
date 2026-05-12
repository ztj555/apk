# AutoDial - 一键拨号系统

> 电脑输入号码 → 一键发送 → 手机自动拨号  
> 专为电销场景设计，支持多人独立使用，互不干扰

---

## 📐 系统架构

```
┌─────────────┐      WebSocket       ┌──────────────────┐      WebSocket       ┌─────────────┐
│   电脑端     │ ◄──────────────────► │   云端中转服务器   │ ◄──────────────────► │   手机端     │
│  (网页面板)  │    发送号码指令        │  (配对验证+路由)   │    接收并拨号        │  (Android)  │
└─────────────┘                      └──────────────────┘                      └─────────────┘
```

**核心流程：**
1. 手机打开 APP → 生成 6 位配对码
2. 电脑打开网页 → 输入配对码 → 完成绑定
3. 电脑输入号码 → 选择 SIM 卡 → 点拨号
4. 手机弹出确认窗口 → 确认后自动拨号

**安全机制：**
- 一台电脑只能配对一台手机（6 位唯一配对码）
- 多人各自配对，互不干扰
- 支持随时解绑重配

---

## 📁 项目结构

```
AutoDial/
├── server/                    # 中转服务器（Node.js）
│   ├── package.json
│   └── server.js              # WebSocket 服务器 + 配对逻辑
│
├── web/                       # 电脑端网页面板
│   └── index.html             # 单文件网页，双击即可使用
│
├── android/                   # Android 手机端 APP
│   ├── build.gradle           # 根项目配置
│   ├── settings.gradle
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle       # APP 依赖配置
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           └── java/com/autodial/app/
│               ├── MainActivity.kt          # 主界面（配置+配对）
│               ├── DialService.kt           # 后台服务（WebSocket长连接）
│               ├── DialConfirmActivity.kt   # 拨号确认弹窗（选SIM卡）
│               └── BootReceiver.kt          # 开机自启
│
└── README.md                  # 本文件
```

---

## 🚀 部署指南

### 第一步：部署中转服务器（免费）

有三种免费方案，任选其一：

#### 方案 A：Render.com（推荐，最简单）

1. 注册 https://render.com （GitHub 账号登录）
2. 点击 **New** → **Web Service**
3. 连接你的 GitHub 仓库，或直接创建
4. 配置如下：
   - **Build Command**: `cd server && npm install`
   - **Start Command**: `cd server && node server.js`
   - **Instance Type**: Free
5. 部署完成后会得到地址，如 `https://autodial-xxxx.onrender.com`
6. WebSocket 地址就是：`wss://autodial-xxxx.onrender.com/ws`

#### 方案 B：Railway.app

1. 注册 https://railway.app
2. 新建项目 → 部署 Node.js 服务
3. 上传 `server/` 目录
4. 添加环境变量 `PORT=3000`
5. 获取公网 URL

#### 方案 C：本地测试（仅局域网）

```bash
cd server
npm install
npm start
```

服务器运行在 `ws://localhost:3000/ws`

> ⚠️ 本地方案仅适合测试，手机和电脑需在同一局域网

---

### 第二步：手机端 APP

#### 方式 1：使用 Android Studio 编译（需要开发环境）

1. 用 Android Studio 打开 `android/` 目录
2. 等待 Gradle 同步完成
3. Build → Build APK(s)
4. 将生成的 APK 传到手机安装

#### 方式 2：使用 GitHub Actions 自动构建

在仓库根目录创建 `.github/workflows/build.yml`，提交代码后自动构建 APK。

#### 方式 3：在线打包（推荐，最简单）

1. 访问 https://www.apkbuilder.net 或使用 GitHub Codespaces
2. 上传 `android/` 目录
3. 在线编译并下载 APK

---

### 第三步：电脑端

**无需安装**，直接用浏览器打开 `web/index.html` 即可。

也可以部署到公司内网服务器，同事们通过 URL 访问。

---

## 📱 使用流程

### 首次使用（配对）

```
手机端                              电脑端
  │                                   │
  │ 1. 安装 APP                       │ 1. 打开网页 index.html
  │ 2. 输入服务器地址                  │ 2. 输入同一个服务器地址
  │ 3. 点击「连接」                    │ 3. 点击「连接」
  │ 4. 点击「生成配对码」              │
  │    显示: 385726                   │
  │                                   │ 4. 输入 385726
  │                                   │ 5. 点击「配对」
  │ ◄─── 配对成功！ ────────────────► │
  │                                   │
  │ 5. 保持 APP 后台运行              │ 6. 输入客户号码
  │                                   │ 7. 选择 SIM 卡（1 或 2）
  │                                   │ 8. 点击「立即拨号」
  │ ◄──── 收到拨号指令 ────────────── │
  │ 6. 弹窗确认 → 选择卡 → 确认拨号    │
  │                                   │
```

### 日常使用

1. 手机打开 APP（后台运行即可，支持开机自启）
2. 电脑打开网页
3. 输入号码 → 选卡 → 拨号 → 手机确认 → 完成

---

## ⚙️ 手机端权限说明

安装 APP 后需要授权以下权限：

| 权限 | 用途 | 必需 |
|------|------|------|
| 拨号 (CALL_PHONE) | 执行拨号操作 | ✅ 是 |
| 网络访问 (INTERNET) | 连接服务器 | ✅ 是 |
| 通知 (POST_NOTIFICATIONS) | 显示拨号通知 | ⚠️ 建议 |
| 前台服务 (FOREGROUND_SERVICE) | 保持后台连接 | ⚠️ 建议 |
| 开机自启 (RECEIVE_BOOT_COMPLETED) | 开机自动启动 | ❌ 可选 |

> MIUI 系统（红米手机）需额外设置：  
> 设置 → 应用设置 → 应用管理 → AutoDial → 省电策略改为「无限制」  
> 设置 → 应用设置 → 自启动管理 → AutoDial → 开启自启动

---

## 🔧 故障排查

### 手机收不到拨号指令
1. 检查 APP 是否在运行（通知栏应有常驻通知）
2. 检查网络连接是否正常
3. 电脑端查看手机是否显示「在线」
4. MIUI 系统：检查是否被省电策略限制

### 连接不上服务器
1. 检查服务器地址是否正确（注意 `wss://` 前缀）
2. 服务器是否正常运行
3. Render 免费版有冷启动（15分钟无请求会休眠，首次访问需等30秒）

### 双卡选择不生效
1. 部分机型 Telecomm API 受限，可能无法指定 SIM 卡
2. 如遇此情况，默认使用系统当前激活的 SIM 卡
3. 可在系统设置中手动切换默认 SIM 卡作为替代方案

### 配对码无效
1. 配对码有效期：生成后 10 分钟
2. 超时需在手机端重新生成
3. 一个配对码只能使用一次

---

## 📋 给公司 IT 管理员的部署建议

### 推荐部署方式

1. **服务器**：部署 Render.com 免费版，公司内所有同事共用一个中转服务器
2. **网页**：放在公司内网 IIS/Nginx，同事通过内网 URL 访问（可加入域名收藏夹）
3. **APP**：统一编译一个 APK，通过公司内部渠道分发
4. **管理**：每台手机和电脑独立配对，互不影响

### 团队规模

- 免费版 Render.com 支持约 **50-100 个并发连接**
- 对于 50 人以下的电销团队完全够用
- 如需更大规模，考虑升级 Render 付费版或自建服务器

---

## 📄 技术栈

| 组件 | 技术 |
|------|------|
| 中转服务器 | Node.js + Express + WebSocket (ws) |
| 电脑端 | 纯 HTML/CSS/JS，无需框架 |
| 手机端 | Android (Kotlin) + OkHttp + Coroutines |
| 通信协议 | WebSocket (JSON) |

---

## ⚖️ 注意事项

1. 本系统仅用于合法的商业电话拨打
2. 请遵守当地电信法规，不得用于骚扰电话
3. 系统不存储通话内容，仅转发拨号指令
4. 建议公司内部建立使用管理制度
