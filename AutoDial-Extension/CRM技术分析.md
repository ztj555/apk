# CRM 网页技术分析文档

> 分析对象：guwen.zhudaicms.com（助贷通 CRM）
> 分析日期：2026-04-28

---

## 一、页面整体架构

### 1.1 三层 iframe 嵌套结构

```
┌─────────────────────────────────────────────────────────────┐
│ 顶层页面: /manage/index/index.html                          │
│  ┌───────────────────────────────────────────────────────┐ │
│  │ iframe (ref=e62)                                       │ │
│  │  第一层 iframe: /manage/kefu_clients/index.html       │ │
│  │  ┌───────────────────────────────────────────────────┐│ │
│  │  │ iframe (ref=e65)                                   ││ │
│  │  │  第二层 iframe (ref=f2) → 客户详情页面             ││ │
│  │  │    ├─ 左侧导航列表（基本信息/身份信息/房产...）   ││ │
│  │  │    └─ 右侧详情内容（字段名 + 值）                 ││ │
│  │  └───────────────────────────────────────────────────┘│ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

**关键点：** 插件必须用 `all_frames: true` 才能在所有 iframe 里注入 content script。浮动按钮只在顶层页面创建。

---

## 二、详情页面 HTML 结构（重点）

### 2.1 字段行的 DOM 结构

CRM 的每个字段行都是标准的 `listitem` + 双 `generic` 结构：

```
<li class="listitem">                           ← listitem [ref=f2e20]
  ├─ <span>手机号码：</span>                   ← generic [ref=f2e21]（标签）
  └─ <span>                                    ← generic [ref=f2e22]（值容器）
       ├─ text node: "15757142750"            ← 手机号在这里
       └─ <p>
            └─ <a href="##">点击拨打</a>      ← CRM 自带的空链接
```

**其他字段同理：**
```
listitem [ref=f2e17] → 姓名：
  ├─ generic [f2e18]: "姓名："
  └─ generic [f2e19]: "童永平"

listitem [ref=f2e25] → 来源：
  ├─ generic [f2e26]: "来源："
  └─ generic [f2e27]: "T+1-excel"
```

### 2.2 识别特征

| 特征 | 值 |
|------|-----|
| 标签容器 | `generic` / `span`（文本 = 字段名） |
| 值容器 | 相邻的下一个 `generic` / `span` |
| 手机号 | 值容器的**第一个文本节点**（不是 innerHTML，避免混入链接文字） |
| CRM 原链接 | `a[href="##"]` 且文本是"点击拨打" |

---

## 三、为什么之前定位错了？

### ❌ 错误写法（遍历所有 li，逐层查找）

```javascript
const allEls = document.querySelectorAll('li, .list-item, [class*="item"]');
for (const li of allEls) {
  const labelEl = li.querySelector('*');  // ❌ 这里查的是第一个子元素
  const labelText = labelEl.textContent.trim();
  if (labelText !== '手机号码：') continue;

  const valueEl = labelEl.nextElementSibling;  // ❌ nextElementSibling 是 labelEl 的下一个兄弟，不是 li 的
  // ...
}
```

**问题：** `li.querySelector('*')` 选的是第一个**子元素节点**（跳过文本节点），而 CRM 结构中标签"手机号码："本身就是一个文本节点，直接在 `li` 的第一个 child 位置：

```
li
  ├─ text: ""                          ← 空文本节点（可能被跳过）
  ├─ generic "手机号码："              ← querySelector('*') 会选到这个
  └─ generic "15757142750" ...
```

但实际上 `li.querySelector('*')` 会选到 `generic` 元素（"手机号码："），这个是对的。真正的问题是 `labelEl.nextElementSibling` —— 如果 `li` 里还有空白文本节点，nextElementSibling 可能跳过。

### ✅ 正确写法（TreeWalker 直接定位文本节点）

```javascript
// 用 TreeWalker 直接找文本内容为"手机号码："的文本节点
const walker = document.createTreeWalker(
  document.body,
  NodeFilter.SHOW_TEXT,
  {
    acceptNode: node => {
      const t = node.textContent.trim();
      return (t === '手机号码：' || t === '手机号码:')
        ? NodeFilter.FILTER_ACCEPT
        : NodeFilter.FILTER_REJECT;
    }
  }
);

while (walker.nextNode()) {
  const labelNode = walker.currentNode;  // 直接命中文本节点

  // 标签的父元素（generic）再找下一个兄弟（值容器）
  const valueEl = labelNode.parentElement.nextElementSibling;

  // 手机号在值容器的第一个文本节点
  const phone = valueEl.firstChild.textContent.trim().match(/^1[3-9]\d{9}$/)?.[1];
}
```

**优势：**
- TreeWalker 专门遍历文本节点，**不会错过任何位置的文本**
- 不依赖特定的选择器或 DOM 层级
- 对页面结构变化（换 div/span/li）都有一定容错

---

## 四、关键 API 详解

### 4.1 TreeWalker

```javascript
const walker = document.createTreeWalker(
  root,           // 从哪个元素开始遍历（这里是 document.body）
  whatToShow,     // 监听什么类型的节点
  filter          // 过滤器函数
);

// 遍历所有匹配的文本节点
while (walker.nextNode()) {
  console.log(walker.currentNode.textContent);
}
```

**`whatToShow` 常用值：**
```javascript
NodeFilter.SHOW_ELEMENT    // 元素节点
NodeFilter.SHOW_TEXT       // 文本节点
NodeFilter.SHOW_ALL        // 所有节点
```

### 4.2 从值容器提取手机号的正确方式

```javascript
const valueEl = labelNode.parentElement.nextElementSibling;

// ❌ 错误：innerText/textContent 会包含链接等所有文字
valueEl.textContent.trim()  // "15757142750点击拨打" ← 混入了链接文字

// ✅ 正确：取第一个文本节点
valueEl.firstChild.textContent.trim()
// 或者
valueEl.childNodes[0].textContent.trim()

// ✅ 更健壮：遍历子节点，找第一个符合手机号格式的文本节点
for (const node of valueEl.childNodes) {
  if (node.nodeType === Node.TEXT_NODE) {
    const phone = node.textContent.trim().match(/^1[3-9]\d{9}$/)?.[1];
    if (phone) { console.log(phone); break; }
  }
}
```

### 4.3 拦截 CRM 原链接

```javascript
const dialLink = valueEl.querySelector('a[href="##"]');
if (dialLink) {
  dialLink.addEventListener('click', (e) => {
    e.preventDefault();    // 阻止默认行为（CRM的href="##"是空跳转）
    e.stopPropagation();   // 阻止事件冒泡
    doDial(phone);         // 执行我们的拨号逻辑
  });
}
```

---

## 五、iframe 通信架构

```
第二层iframe（详情页）
  └─ content script 检测到手机号
       └─ chrome.runtime.sendMessage({type:'phoneDetected', phone})
            └─ background.js（Service Worker）
                 └─ chrome.tabs.sendMessage(tabId, {type:'updatePhone'}, {frameId:0})
                      └─ 顶层页面（frameId=0）
                           └─ 更新浮动按钮显示
```

**为什么用 frameId=0？**
- `frameId: 0` 代表**顶层页面 frame**，不管中间嵌套了多少层 iframe，都能命中
- 如果不指定 frameId，消息会发到第一个加载的 frame，不一定是顶层

---

## 六、快速识别任意网页字段的方法

用 Playwright + `snapshot` 命令抓取页面 DOM：

```bash
playwright-cli snapshot --filename=crm-detail.yaml
```

然后搜索目标字段：
```powershell
Get-Content "crm-detail.yaml" | Select-String -Pattern "手机号码" -Context 3,3
```

输出会显示该字段的完整 DOM 路径和周围结构。

---

## 七、总结：精准定位三步法

1. **抓 DOM**：用 Playwright snapshot 拿到页面结构
2. **找规律**：看标签和值在 DOM 里是什么关系（父子？兄弟？）
3. **选工具**：
   - 关系明确（父子/兄弟）→ `querySelector` + 导航 API
   - 关系不明确或文本分散 → `TreeWalker` 遍历文本节点
   - 动态内容 → `MutationObserver` 监听变化
