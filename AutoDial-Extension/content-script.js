/**
 * AutoDial Content Script v3.0
 * 1. 主题系统（参考手机端/PC端16套主题）
 * 2. 拨号悬浮按钮 + 挂断悬浮按钮（均主题化、可拖动）
 * 3. 右键菜单（含主题切换入口）
 * 4. 子iframe扫描手机号
 */
(function () {
  'use strict';
  if (window.__adv2) return;
  window.__adv2 = true;

  const isTopFrame = (window === window.top);
  console.log('[AutoDial v3]', isTopFrame ? '顶层页面' : '子iframe', window.location.href);

  // ═══════════════════════════════════════════════════════════════
  // 主题数据（精选8套，适配插件端场景）
  // ═══════════════════════════════════════════════════════════════
  const EXT_THEMES = {
    'dark-gold': {
      name: '暗金', icon: '✦',
      accent: '#C9A84C', accentLight: '#F0C040', accentDark: '#8B6914',
      bg: '#111318', bg2: '#1A1D24', bg3: '#22262F',
      text: '#E8DCC8', text2: '#A09070',
      green: '#2ECC71', red: '#E74C3C',
      gradAccent: 'linear-gradient(135deg,#C9A84C,#8B6914)',
      gradIdle: 'linear-gradient(135deg,#5b5b5b,#333)',
      gradGreen: 'linear-gradient(135deg,#2ECC71,#27AE60)',
      gradRed: 'linear-gradient(135deg,#E74C3C,#C0392B)',
    },
    'cyber-frost': {
      name: '冰蓝冷峻', icon: '❄',
      accent: '#00BCD4', accentLight: '#4DD0E1', accentDark: '#006064',
      bg: '#0A1628', bg2: '#122A45', bg3: '#1A3A5C',
      text: '#E0F0FF', text2: '#7BA3C4',
      green: '#00E676', red: '#FF5252',
      gradAccent: 'linear-gradient(135deg,#00BCD4,#006064)',
      gradIdle: 'linear-gradient(135deg,#1A3A5C,#0A1628)',
      gradGreen: 'linear-gradient(135deg,#00E676,#00C853)',
      gradRed: 'linear-gradient(135deg,#FF5252,#D32F2F)',
    },
    'deep-space': {
      name: '深空紫', icon: '◆',
      accent: '#BB86FC', accentLight: '#DA98FF', accentDark: '#7B1FA2',
      bg: '#0D0A18', bg2: '#18142E', bg3: '#241E42',
      text: '#E8DEFF', text2: '#9575CD',
      green: '#00E676', red: '#FF5252',
      gradAccent: 'linear-gradient(135deg,#BB86FC,#7B1FA2)',
      gradIdle: 'linear-gradient(135deg,#241E42,#0D0A18)',
      gradGreen: 'linear-gradient(135deg,#00E676,#00C853)',
      gradRed: 'linear-gradient(135deg,#FF5252,#C0392B)',
    },
    'cyberpunk': {
      name: '赛博朋克', icon: '⚡',
      accent: '#00FFFF', accentLight: '#80FFFF', accentDark: '#008B8B',
      bg: '#0A0010', bg2: '#150022', bg3: '#220035',
      text: '#F0F0FF', text2: '#8866CC',
      green: '#39FF14', red: '#FF0039',
      gradAccent: 'linear-gradient(135deg,#00FFFF,#008B8B)',
      gradIdle: 'linear-gradient(135deg,#220035,#0A0010)',
      gradGreen: 'linear-gradient(135deg,#39FF14,#00C853)',
      gradRed: 'linear-gradient(135deg,#FF0039,#C0392B)',
    },
    'minimalist': {
      name: '极简白', icon: '○',
      accent: '#888888', accentLight: '#AAAAAA', accentDark: '#666666',
      bg: '#1A1A1A', bg2: '#2A2A2A', bg3: '#3A3A3A',
      text: '#E8E8E8', text2: '#999999',
      green: '#4CAF50', red: '#EF5350',
      gradAccent: 'linear-gradient(135deg,#888888,#666666)',
      gradIdle: 'linear-gradient(135deg,#3A3A3A,#1A1A1A)',
      gradGreen: 'linear-gradient(135deg,#4CAF50,#388E3C)',
      gradRed: 'linear-gradient(135deg,#EF5350,#C62828)',
    },
    'forest-green': {
      name: '森林绿', icon: '♣',
      accent: '#81C784', accentLight: '#A5D6A7', accentDark: '#388E3C',
      bg: '#0E1810', bg2: '#182818', bg3: '#223822',
      text: '#E0F0E0', text2: '#7AA07A',
      green: '#69F0AE', red: '#FF8A80',
      gradAccent: 'linear-gradient(135deg,#81C784,#388E3C)',
      gradIdle: 'linear-gradient(135deg,#223822,#0E1810)',
      gradGreen: 'linear-gradient(135deg,#69F0AE,#00E676)',
      gradRed: 'linear-gradient(135deg,#FF8A80,#E74C3C)',
    },
    'energetic-orange': {
      name: '活力橙', icon: '☀',
      accent: '#FF9800', accentLight: '#FFB74D', accentDark: '#E65100',
      bg: '#1A1510', bg2: '#2A2018', bg3: '#3A2D20',
      text: '#FFF5E6', text2: '#B08D60',
      green: '#66BB6A', red: '#EF5350',
      gradAccent: 'linear-gradient(135deg,#FF9800,#E65100)',
      gradIdle: 'linear-gradient(135deg,#3A2D20,#1A1510)',
      gradGreen: 'linear-gradient(135deg,#66BB6A,#388E3C)',
      gradRed: 'linear-gradient(135deg,#EF5350,#C62828)',
    },
    'ocean-blue': {
      name: '海洋蓝', icon: '◎',
      accent: '#42A5F5', accentLight: '#64B5F6', accentDark: '#1565C0',
      bg: '#0B1424', bg2: '#152238', bg3: '#1E3050',
      text: '#E0ECFF', text2: '#7890B8',
      green: '#00E676', red: '#FF5252',
      gradAccent: 'linear-gradient(135deg,#42A5F5,#1565C0)',
      gradIdle: 'linear-gradient(135deg,#1E3050,#0B1424)',
      gradGreen: 'linear-gradient(135deg,#00E676,#00C853)',
      gradRed: 'linear-gradient(135deg,#FF5252,#C62828)',
    },
  };

  // 当前主题
  let currentThemeId = localStorage.getItem('__ad_theme') || 'dark-gold';
  function T() { return EXT_THEMES[currentThemeId] || EXT_THEMES['dark-gold']; }

  function applyTheme(id) {
    currentThemeId = id;
    localStorage.setItem('__ad_theme', id);
    const t = T();
    // 刷新拨号按钮（完整刷新所有主题相关属性）
    if (floatEl) {
      floatEl.style.background = currentPhone ? t.gradAccent : t.gradIdle;
      floatEl.style.color = t.text;
      floatEl.style.boxShadow = `0 4px 16px ${t.accent}22`;
      floatEl.style.border = `1px solid ${t.accent}33`;
    }
    // 刷新挂断按钮（跟随主题 idle 颜色）
    if (hangupEl) {
      hangupEl.style.background = t.gradIdle;
      hangupEl.style.color = t.text;
      hangupEl.style.boxShadow = `0 2px 10px ${t.accent}33`;
      hangupEl.style.border = `1px solid ${t.accent}33`;
      const label = hangupEl.querySelector('span');
      if (label) label.style.color = t.text;
    }
    // 刷新缩放手柄颜色（用 accent 而非 red）
    if (hangupResizeHandle) {
      hangupResizeHandle.style.background = `linear-gradient(135deg, ${t.accent}66 50%, transparent 50%)`;
    }
    // 刷新右键菜单（如果打开的话）
    hideContextMenu();
    // 广播主题变更给子 iframe，刷新"点击拨打"链接颜色
    if (isTopFrame) {
      try {
        document.querySelectorAll('iframe').forEach(iframe => {
          iframe.contentWindow?.postMessage({ type: '__ad_theme_change', accent: t.accent }, '*');
        });
      } catch (_) {}
    }
  }

  // ═══════════════════════════════════════════════
  // 顶层页面：创建浮动拖动按钮
  // ═══════════════════════════════════════════════
  if (isTopFrame) {
    let floatEl = null;
    let currentPhone = null;

    function createFloat() {
      if (document.getElementById('__ad_float')) return;
      const t = T();

      floatEl = document.createElement('div');
      floatEl.id = '__ad_float';
      Object.assign(floatEl.style, {
        position: 'fixed',
        left: '1415px',
        top: '370px',
        zIndex: '2147483647',
        padding: '10px 20px',
        fontSize: '13px',
        fontWeight: '600',
        color: t.text,
        background: t.gradIdle,
        borderRadius: '24px',
        boxShadow: `0 4px 16px ${t.accent}22`,
        cursor: 'grab',
        userSelect: 'none',
        transition: 'background .2s, box-shadow .2s',
        whiteSpace: 'nowrap',
        letterSpacing: '0.5px',
        border: `1px solid ${t.accent}33`,
      });
      // 用 span 包文字，避免 textContent 覆盖子元素
      const dialLabel = document.createElement('span');
      dialLabel.id = '__ad_dial_label';
      dialLabel.textContent = '📞 等待号码...';
      dialLabel.style.pointerEvents = 'none'; // 不拦截指针事件，让父元素处理
      floatEl.appendChild(dialLabel);

      // ─── 拖动（仅左右边缘启动，中间区域点击拨号） ────
      let dragging = false, dragStartX = 0, dragStartY = 0, ox = 0, oy = 0;
      const DRAG_EDGE = 0.18; // 左右各 18% 为拖动区域
      floatEl.addEventListener('pointerdown', (e) => {
        dragStartX = e.clientX;
        dragStartY = e.clientY;
        const r = floatEl.getBoundingClientRect();
        const xRatio = (e.clientX - r.left) / r.width;
        // 中间区域（号码/表情）：不启动拖动，允许 click 正常触发
        if (xRatio > DRAG_EDGE && xRatio < (1 - DRAG_EDGE)) return;
        dragging = true;
        ox = e.clientX - r.left;
        oy = e.clientY - r.top;
        floatEl.setPointerCapture(e.pointerId);
        floatEl.style.cursor = 'grabbing';
        e.preventDefault();
      });
      floatEl.addEventListener('pointermove', (e) => {
        if (!dragging) return;
        floatEl.style.left = (e.clientX - ox) + 'px';
        floatEl.style.top = (e.clientY - oy) + 'px';
        floatEl.style.right = 'auto';
        floatEl.style.bottom = 'auto';
      });
      floatEl.addEventListener('pointerup', () => {
        dragging = false;
        floatEl.style.cursor = 'grab';
      });

      // ─── 点击拨号 ────────────────────────────────
      floatEl.addEventListener('click', (e) => {
        // 比较按下和抬起的位置，超过 5px 视为拖动，不触发拨号
        const dist = Math.hypot(e.clientX - dragStartX, e.clientY - dragStartY);
        if (dist > 5) return;
        if (!currentPhone) {
          flashFloat('未检测到号码', false);
          return;
        }
        chrome.runtime.sendMessage({ type: 'dial', phone: currentPhone });
      });

      // ─── 右键菜单 ────────────────────────────────
      floatEl.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        e.stopPropagation();
        showContextMenu(e.clientX, e.clientY);
      });

      document.body.appendChild(floatEl);
    }

    // ═══════════════════════════════════════════════
    // 挂断悬浮按钮（椭圆 + "挂断"文字 + 主题化 + 左下角拖拽缩放）
    // ═══════════════════════════════════════════════
    let hangupEl = null;
    let hangupResizeHandle = null; // 左下角缩放手柄
    let hangupSize = parseInt(localStorage.getItem('__ad_hangup_size') || '48', 10);
    const HANGUP_MIN = 36, HANGUP_MAX = 100;

    function createHangupBtn() {
      if (document.getElementById('__ad_hangup')) return;
      const t = T();

      hangupEl = document.createElement('div');
      hangupEl.id = '__ad_hangup';
      applyHangupSize(hangupSize);

      Object.assign(hangupEl.style, {
        position: 'fixed',
        left: '1400px',
        top: '140px',
        zIndex: '2147483646',
        borderRadius: '20px',
        background: t.gradIdle,
        boxShadow: `0 2px 10px ${t.accent}33`,
        cursor: 'pointer',
        userSelect: 'none',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        transition: 'box-shadow .2s, background .2s',
        color: t.text,
        fontWeight: '700',
        letterSpacing: '1px',
        border: `1px solid ${t.accent}33`,
      });
      // 用 span 包文字，避免 textContent 覆盖手柄子元素
      const hangupLabel = document.createElement('span');
      hangupLabel.textContent = '挂断';
      hangupLabel.style.pointerEvents = 'none';
      hangupEl.appendChild(hangupLabel);

      // ─── 点击挂断 ────────────────────────────────
      hangupEl.addEventListener('click', (e) => {
        const dist = Math.hypot(e.clientX - hDragStartX, e.clientY - hDragStartY);
        if (dist > 5) return;
        e.stopPropagation();
        chrome.runtime.sendMessage({ type: 'hangup' }, (resp) => {
          if (chrome.runtime.lastError) {
            flashHangup('PC端未运行', false);
            return;
          }
          if (resp && resp.success) flashHangup('已挂断', true);
          else flashHangup(resp?.error || '挂断失败', false);
        });
      });

      // ─── 右键菜单（同拨号按钮） ──────────────────
      hangupEl.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        e.stopPropagation();
        showContextMenu(e.clientX, e.clientY);
      });

      // ─── 拖动（仅左右边缘启动，中间区域点击挂断） ───
      let hDragging = false, hDragStartX = 0, hDragStartY = 0, hOx = 0, hOy = 0;
      const HANGUP_DRAG_EDGE = 0.18;
      hangupEl.addEventListener('pointerdown', (e) => {
        if (hangupResizeHandle && e.target === hangupResizeHandle) return;
        hDragStartX = e.clientX;
        hDragStartY = e.clientY;
        const r = hangupEl.getBoundingClientRect();
        const xRatio = (e.clientX - r.left) / r.width;
        if (xRatio > HANGUP_DRAG_EDGE && xRatio < (1 - HANGUP_DRAG_EDGE)) return;
        hDragging = true;
        hOx = e.clientX - r.left;
        hOy = e.clientY - r.top;
        hangupEl.setPointerCapture(e.pointerId);
        e.preventDefault();
      });
      hangupEl.addEventListener('pointermove', (e) => {
        if (!hDragging) return;
        hangupEl.style.left = (e.clientX - hOx) + 'px';
        hangupEl.style.top = (e.clientY - hOy) + 'px';
        hangupEl.style.right = 'auto';
        hangupEl.style.bottom = 'auto';
      });
      hangupEl.addEventListener('pointerup', () => { hDragging = false; });

      // ─── 左下角缩放手柄 ─────────────────────────
      hangupResizeHandle = document.createElement('div');
      Object.assign(hangupResizeHandle.style, {
        position: 'absolute',
        left: '0px',
        bottom: '0px',
        width: '14px',
        height: '14px',
        cursor: 'nwse-resize',
        zIndex: '1',
        // 用三角形视觉提示
        background: `linear-gradient(135deg, ${t.accent}66 50%, transparent 50%)`,
        borderRadius: '0 0 0 4px',
        opacity: '0.6',
        transition: 'opacity .15s',
      });
      // hover 时手柄更明显
      hangupResizeHandle.addEventListener('mouseenter', () => {
        hangupResizeHandle.style.opacity = '1';
      });
      hangupResizeHandle.addEventListener('mouseleave', () => {
        hangupResizeHandle.style.opacity = '0.6';
      });

      // 缩放拖拽逻辑
      let resizing = false, resizeStartX = 0, resizeStartSize = 0;
      hangupResizeHandle.addEventListener('pointerdown', (e) => {
        e.stopPropagation();
        e.preventDefault();
        resizing = true;
        resizeStartX = e.clientX;
        resizeStartSize = hangupSize;
        hangupResizeHandle.setPointerCapture(e.pointerId);
      });
      hangupResizeHandle.addEventListener('pointermove', (e) => {
        if (!resizing) return;
        const dx = resizeStartX - e.clientX;
        const newSize = Math.min(HANGUP_MAX, Math.max(HANGUP_MIN, resizeStartSize + dx));
        if (newSize !== hangupSize) {
          hangupSize = newSize;
          localStorage.setItem('__ad_hangup_size', hangupSize);
          applyHangupSize(hangupSize);
        }
      });
      hangupResizeHandle.addEventListener('pointerup', () => { resizing = false; });

      hangupEl.appendChild(hangupResizeHandle);
      document.body.appendChild(hangupEl);
    }

    function applyHangupSize(size) {
      if (!hangupEl) return;
      // 椭圆形：宽 = size * 2.0，高 = size * 0.72（扁椭圆，上下不宽）
      const w = Math.round(size * 2.0);
      const h = Math.round(size * 0.72);
      hangupEl.style.width = w + 'px';
      hangupEl.style.height = h + 'px';
      hangupEl.style.fontSize = Math.round(h * 0.45) + 'px';
      hangupEl.style.borderRadius = Math.round(h * 0.45) + 'px';
    }

    function flashHangup(text, ok) {
      if (!hangupEl) return;
      const t = T();
      const h = Math.round(hangupSize * 0.72);
      const label = hangupEl.querySelector('span');
      if (label) label.textContent = text;
      hangupEl.style.fontSize = Math.round(h * 0.38) + 'px';
      hangupEl.style.background = t.gradRed; // 挂断按钮点击后始终显示红色
      setTimeout(() => {
        if (label) label.textContent = '挂断';
        hangupEl.style.fontSize = Math.round(h * 0.45) + 'px';
        hangupEl.style.background = t.gradIdle; // 恢复主题色
      }, 1800);
    }

    // ─── 自定义右键菜单 ──────────────────────────────
    let contextMenu = null;
    let _ctxMousedownHandler = null;

    function showContextMenu(x, y) {
      hideContextMenu();
      const t = T();

      // 全屏透明遮罩层：负责捕获菜单外的所有点击
      const overlay = document.createElement('div');
      overlay.id = '__ad_ctxmenu_overlay';
      Object.assign(overlay.style, {
        position: 'fixed',
        inset: '0',
        zIndex: '2147483646', // 比菜单低 1
        cursor: 'default',
      });
      overlay.addEventListener('mousedown', (e) => {
        e.preventDefault();
        e.stopPropagation();
        hideContextMenu();
      });
      overlay.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        e.stopPropagation();
        hideContextMenu();
      });
      document.body.appendChild(overlay);

      contextMenu = document.createElement('div');
      contextMenu.id = '__ad_ctxmenu';
      Object.assign(contextMenu.style, {
        position: 'fixed',
        left: x + 'px',
        top: y + 'px',
        zIndex: '2147483647',
        background: t.bg2,
        borderRadius: '10px',
        boxShadow: `0 4px 20px ${t.accent}22, 0 0 0 1px ${t.accent}33`,
        padding: '4px 0',
        minWidth: '220px',
        fontFamily: 'system-ui, -apple-system, sans-serif',
        fontSize: '13px',
        color: t.text,
        overflow: 'hidden',
        backdropFilter: 'blur(16px)',
      });

      const items = [
        { label: '🖥 打开电脑端主界面', action: openDesktopApp },
        { label: '📋 显示/隐藏悬浮窗', action: toggleFloatbar },
        { type: 'separator' },
        { label: currentPhone ? '📞 拨打 ' + currentPhone : '📞 拨号（未检测号码）', action: () => {
          if (!currentPhone) { flashFloat('未检测到号码', false); return; }
          chrome.runtime.sendMessage({ type: 'dial', phone: currentPhone });
        }},
        { label: currentPhone ? '💬 发短信 ' + currentPhone : '💬 发短信（未检测号码）', action: () => {
          if (!currentPhone) { flashFloat('未检测到号码', false); return; }
          sendSms(currentPhone);
        }},
        { type: 'separator' },
        { label: '🎨 切换主题', action: showThemeMenu },
        { type: 'separator' },
        { label: '📍 获取当前位置', action: showPosition },
        { type: 'separator' },
        { label: '✕ 关闭菜单', action: () => {} },
      ];

      items.forEach(item => {
        if (item.type === 'separator') {
          const sep = document.createElement('div');
          Object.assign(sep.style, { height: '1px', background: t.accent + '22', margin: '4px 8px' });
          contextMenu.appendChild(sep);
          return;
        }
        const row = document.createElement('div');
        Object.assign(row.style, {
          padding: '8px 14px',
          cursor: 'pointer',
          transition: 'background .15s',
          whiteSpace: 'nowrap',
        });
        row.textContent = item.label;
        row.addEventListener('mouseenter', () => { row.style.background = t.accent + '18'; });
        row.addEventListener('mouseleave', () => { row.style.background = 'transparent'; });
        row.addEventListener('click', (e) => {
          e.stopPropagation();
          hideContextMenu();
          item.action();
        });
        contextMenu.appendChild(row);
      });

      document.body.appendChild(contextMenu);

      requestAnimationFrame(() => {
        const rect = contextMenu.getBoundingClientRect();
        if (rect.right > window.innerWidth) contextMenu.style.left = (window.innerWidth - rect.width - 8) + 'px';
        if (rect.bottom > window.innerHeight) contextMenu.style.top = (window.innerHeight - rect.height - 8) + 'px';
      });

      _ctxMousedownHandler = (e) => {
        const menu = document.getElementById('__ad_ctxmenu');
        if (menu && !menu.contains(e.target)) {
          hideContextMenu();
        }
      };
      // 用 setTimeout 延迟一帧注册，避免与当前右键事件冲突
      setTimeout(() => {
        document.addEventListener('mousedown', _ctxMousedownHandler, true);
      }, 0);
      document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hideContextMenu(); }, { once: true });
    }

    function hideContextMenu() {
      // 移除遮罩层
      const overlay = document.getElementById('__ad_ctxmenu_overlay');
      if (overlay) overlay.remove();
      // 移除菜单
      const el = document.getElementById('__ad_ctxmenu');
      if (el) el.remove();
      contextMenu = null;
    }

    // ─── 获取当前位置（右键菜单"获取当前位置"） ───
    function showPosition() {
      const t = T();
      const tip = document.createElement('div');
      tip.id = '__ad_position_tip';
      Object.assign(tip.style, {
        position: 'fixed',
        left: '50%',
        top: '50%',
        transform: 'translate(-50%, -50%)',
        zIndex: '2147483647',
        background: t.bg2,
        color: t.text,
        borderRadius: '10px',
        boxShadow: `0 4px 20px ${t.accent}44, 0 0 0 1px ${t.accent}44`,
        padding: '16px 20px',
        fontFamily: 'monospace, system-ui',
        fontSize: '13px',
        lineHeight: '1.8',
        minWidth: '280px',
        backdropFilter: 'blur(16px)',
      });

      const title = document.createElement('div');
      title.textContent = '📍 当前按钮位置';
      Object.assign(title.style, { fontWeight: '700', marginBottom: '10px', fontSize: '14px', color: t.accent });
      tip.appendChild(title);

      const lines = [];
      if (floatEl) {
        const r = floatEl.getBoundingClientRect();
        const l = floatEl.style.left || (r.left + 'px');
        const t2 = floatEl.style.top || (r.top + 'px');
        lines.push(`拨号按钮: left=${l}, top=${t2}`);
      }
      if (hangupEl) {
        const r = hangupEl.getBoundingClientRect();
        const l = hangupEl.style.left || (r.left + 'px');
        const t2 = hangupEl.style.top || (r.top + 'px');
        lines.push(`挂断按钮: left=${l}, top=${t2}`);
      }

      lines.forEach(text => {
        const div = document.createElement('div');
        div.textContent = text;
        tip.appendChild(div);
      });

      const copyBtn = document.createElement('button');
      copyBtn.textContent = '📋 复制位置';
      Object.assign(copyBtn.style, {
        marginTop: '12px',
        padding: '6px 14px',
        background: t.gradAccent,
        color: t.bg,
        border: 'none',
        borderRadius: '6px',
        cursor: 'pointer',
        fontWeight: '600',
        fontSize: '12px',
      });
      copyBtn.onclick = () => {
        navigator.clipboard.writeText(lines.join('\n')).then(() => {
          copyBtn.textContent = '✓ 已复制';
          setTimeout(() => { tip.remove(); }, 800);
        });
      };
      tip.appendChild(copyBtn);

      const closeBtn = document.createElement('button');
      closeBtn.textContent = '关闭';
      Object.assign(closeBtn.style, {
        marginTop: '8px',
        marginLeft: '8px',
        padding: '6px 14px',
        background: 'transparent',
        color: t.text2,
        border: `1px solid ${t.accent}44`,
        borderRadius: '6px',
        cursor: 'pointer',
        fontSize: '12px',
      });
      closeBtn.onclick = () => tip.remove();
      tip.appendChild(closeBtn);

      document.body.appendChild(tip);
    }

    // ─── 主题选择子菜单 ──────────────────────────────
    function showThemeMenu() {
      const t = T();
      const menu = document.createElement('div');
      menu.id = '__ad_thememenu';
      Object.assign(menu.style, {
        position: 'fixed',
        right: '20px',
        bottom: '140px',
        zIndex: '2147483647',
        background: t.bg,
        borderRadius: '12px',
        boxShadow: `0 8px 32px ${t.accent}33, 0 0 0 1px ${t.accent}44`,
        padding: '12px',
        width: '200px',
        fontFamily: 'system-ui, -apple-system, sans-serif',
        fontSize: '13px',
        color: t.text,
        backdropFilter: 'blur(20px)',
      });

      const title = document.createElement('div');
      Object.assign(title.style, {
        fontSize: '12px',
        color: t.text2,
        marginBottom: '8px',
        fontWeight: '500',
        letterSpacing: '1px',
      });
      title.textContent = '🎨 选择主题';
      menu.appendChild(title);

      // 主题列表
      Object.entries(EXT_THEMES).forEach(([id, theme]) => {
        const row = document.createElement('div');
        const isActive = id === currentThemeId;
        Object.assign(row.style, {
          padding: '8px 10px',
          borderRadius: '8px',
          cursor: isActive ? 'default' : 'pointer',
          display: 'flex',
          alignItems: 'center',
          gap: '10px',
          marginBottom: '2px',
          transition: 'background .15s',
          background: isActive ? theme.accent + '22' : 'transparent',
          borderLeft: isActive ? `3px solid ${theme.accent}` : '3px solid transparent',
        });

        // 色块预览
        const swatch = document.createElement('span');
        Object.assign(swatch.style, {
          width: '20px',
          height: '20px',
          borderRadius: '50%',
          background: theme.gradAccent,
          display: 'inline-block',
          flexShrink: '0',
          boxShadow: `0 1px 4px ${theme.accent}55`,
        });
        row.appendChild(swatch);

        // 名称
        const label = document.createElement('span');
        label.textContent = theme.icon + ' ' + theme.name;
        label.style.color = isActive ? theme.accent : theme.text;
        label.style.fontWeight = isActive ? '600' : '400';
        row.appendChild(label);

        if (!isActive) {
          row.addEventListener('mouseenter', () => { row.style.background = theme.accent + '12'; });
          row.addEventListener('mouseleave', () => { row.style.background = 'transparent'; });
        }

        row.addEventListener('click', () => {
          applyTheme(id);
          document.getElementById('__ad_thememenu')?.remove();
        });

        menu.appendChild(row);
      });

      // 关闭按钮
      const closeRow = document.createElement('div');
      Object.assign(closeRow.style, {
        marginTop: '8px',
        paddingTop: '8px',
        borderTop: `1px solid ${t.accent}22`,
        textAlign: 'center',
        color: t.text2,
        cursor: 'pointer',
        fontSize: '12px',
      });
      closeRow.textContent = '关闭';
      closeRow.addEventListener('click', () => menu.remove());
      menu.appendChild(closeRow);

      document.body.appendChild(menu);

      // 点击外部关闭
      const closeHandler = (e) => {
        if (!menu.contains(e.target)) {
          menu.remove();
          document.removeEventListener('mousedown', closeHandler, true);
        }
      };
      setTimeout(() => document.addEventListener('mousedown', closeHandler, true), 100);
    }

    function openDesktopApp() {
      chrome.runtime.sendMessage({ type: 'openDesktop' }, (resp) => {
        if (chrome.runtime.lastError) { flashFloat('PC端未运行', false); return; }
        if (resp && resp.success) flashFloat('已打开主界面', true);
        else flashFloat('打开失败', false);
      });
    }

    function toggleFloatbar() {
      chrome.runtime.sendMessage({ type: 'toggleFloatbar' }, (resp) => {
        if (chrome.runtime.lastError) { flashFloat('PC端未运行', false); return; }
        if (resp && resp.success) flashFloat(resp.visible ? '悬浮窗已显示' : '悬浮窗已隐藏', true);
        else flashFloat('操作失败', false);
      });
    }

    function sendSms(phone) {
      chrome.runtime.sendMessage({ type: 'sendSms', phone }, (resp) => {
        if (chrome.runtime.lastError) { flashFloat('PC端未运行', false); return; }
        if (resp && resp.success) flashFloat('已打开短信窗口', true);
        else flashFloat('打开短信窗口失败', false);
      });
    }

    function updatePhone(phone) {
      currentPhone = phone;
      if (!floatEl) return;
      const t = T();
      const label = document.getElementById('__ad_dial_label');
      if (label) label.textContent = '📞 ' + phone;
      floatEl.style.background = t.gradAccent;
      floatEl.style.boxShadow = `0 4px 16px ${t.accent}33`;
    }

    function flashFloat(text, ok) {
      if (!floatEl) return;
      const t = T();
      const label = document.getElementById('__ad_dial_label');
      if (label) label.textContent = (ok ? '✓ ' : '✗ ') + text;
      floatEl.style.background = ok ? t.gradGreen : t.gradRed;
      floatEl.style.boxShadow = ok
        ? `0 4px 16px ${t.green}44`
        : `0 4px 16px ${t.red}44`;
      setTimeout(() => {
        const lb = document.getElementById('__ad_dial_label');
        if (lb) lb.textContent = currentPhone ? '📞 ' + currentPhone : '📞 等待号码...';
        floatEl.style.background = currentPhone ? t.gradAccent : t.gradIdle;
        floatEl.style.boxShadow = `0 4px 16px ${t.accent}22`;
      }, 2500);
    }

    // 等DOM就绪后创建
    if (document.body) { createFloat(); createHangupBtn(); }
    else document.addEventListener('DOMContentLoaded', () => { createFloat(); createHangupBtn(); });

    // 监听来自background的消息（跨frame通信）
    chrome.runtime.onMessage.addListener((msg) => {
      if (msg.type === 'updatePhone') updatePhone(msg.phone);
      if (msg.type === 'dialResult') flashFloat(msg.ok ? '已拨出' : (msg.err || '失败'), msg.ok);
    });

    return; // 顶层页面只做浮动按钮，不做手机号扫描
  }

  // ═══════════════════════════════════════════════
  // 子iframe：扫描手机号并拦截"点击拨打"
  // ═══════════════════════════════════════════════

  // 监听主题变更，刷新"点击拨打"链接颜色
  window.addEventListener('message', (e) => {
    if (e.data && e.data.type === '__ad_theme_change' && e.data.accent) {
      document.querySelectorAll('.__ad-dial-link').forEach(link => {
        link.style.setProperty('color', e.data.accent, 'important');
      });
    }
  });

  function getPhoneFromDetailPage() {
    const walker = document.createTreeWalker(
      document.body,
      NodeFilter.SHOW_TEXT,
      { acceptNode: node => node.textContent.trim() === '手机号码：' || node.textContent.trim() === '手机号码:' ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT }
    );

    while (walker.nextNode()) {
      const labelNode = walker.currentNode;
      const valueEl = labelNode.parentElement?.nextElementSibling;
      if (!valueEl) continue;

      const raw = valueEl.firstChild?.textContent?.trim() || '';
      const phone = raw.match(/^(1[3-9]\d{9})/)?.[1];
      if (!phone) continue;

      console.log('[AutoDial v3] ✓ 检测到客户手机号:', phone);

      chrome.runtime.sendMessage({ type: 'phoneDetected', phone });

      const dialLink = valueEl.querySelector('a');
      if (dialLink && !dialLink.__adHooked) {
        dialLink.__adHooked = true;
        dialLink.addEventListener('click', (e) => {
          e.preventDefault();
          e.stopPropagation();
          console.log('[AutoDial v3] 点击拨打:', phone);
          chrome.runtime.sendMessage({ type: 'dial', phone });
        });
        dialLink.classList.add('__ad-dial-link');
        dialLink.style.cssText += `;color:${T().accent}!important;font-weight:bold;`;
        console.log('[AutoDial v3] ✓ 已拦截"点击拨打"链接');
      }

      return phone;
    }

    return null;
  }

  function scan() {
    const phone = getPhoneFromDetailPage();
    if (phone) return;
  }

  if (document.body) {
    scan();
  }

  setTimeout(scan, 100);

  const obs = new MutationObserver(() => {
    clearTimeout(scan._timer);
    scan._timer = setTimeout(scan, 150);
  });
  if (document.body) {
    obs.observe(document.body, { childList: true, subtree: true });
  } else {
    document.addEventListener('DOMContentLoaded', () => {
      obs.observe(document.body, { childList: true, subtree: true });
      scan();
    });
  }
})();
