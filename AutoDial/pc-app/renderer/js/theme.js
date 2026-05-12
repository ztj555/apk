/**
 * AutoDial PC端 - 主题引擎
 * 负责 CSS 变量注入、主题应用、跨窗口同步
 * 支持 dark / dusk / dawn / twilight / warm / mist / light 七种亮度模式
 */

(function() {
  'use strict';

  let currentThemeId = DEFAULT_THEME || 'dark-gold';
  let currentMode = DEFAULT_MODE || 'dark';

  // 查找主题数据
  function findTheme(id) {
    return THEME_DATA.find(t => t.id === id) || THEME_DATA[0];
  }

  // 将对象写入 document.documentElement.style
  function setCSSVars(vars) {
    const root = document.documentElement;
    for (const [key, value] of Object.entries(vars)) {
      const cssKey = '--' + key.replace(/([A-Z])/g, '-$1').toLowerCase();
      root.style.setProperty(cssKey, value);
    }
  }

  // CSS变量名映射：JS驼峰 -> CSS连字符
  const COLOR_MAP = {
    gold: 'gold', goldLight: 'gold-light', goldDark: 'gold-dark',
    bg: 'bg', bg2: 'bg2', bg3: 'bg3',
    text: 'text', text2: 'text2',
    green: 'green', red: 'red',
    floatbarBg: 'floatbar-bg', floatbarBorder: 'floatbar-border', floatbarBlur: 'floatbar-blur'
  };

  const STYLE_MAP = {
    radiusSm: 'radius-sm', radiusMd: 'radius-md', radiusLg: 'radius-lg',
    shadow: 'shadow', fontFamily: 'font-family',
    gradientGreen: 'gradient-green', gradientRed: 'gradient-red',
    glowText: 'glow-text', backdropFilter: 'backdrop-filter'
  };

  // 应用主题（主入口）
  function applyTheme(themeId, mode) {
    currentThemeId = themeId || currentThemeId;
    currentMode = mode || currentMode;

    const theme = findTheme(currentThemeId);
    // 优先使用指定模式，没有则回退
    const colors = theme[currentMode] || theme.dark;

    // 注入颜色变量（含 floatbar 相关）
    const colorVars = {};
    for (const [jsKey, cssName] of Object.entries(COLOR_MAP)) {
      if (colors[jsKey] !== undefined) {
        colorVars[jsKey] = colors[jsKey];
      }
    }
    setCSSVars(colorVars);

    // 注入风格变量
    if (theme.style) {
      setCSSVars(theme.style);
    }

    // 发光文字效果
    if (theme.style.glowText && theme.style.glowText !== 'none') {
      const root = document.documentElement;
      root.style.setProperty('--glow-text', theme.style.glowText);
    } else {
      document.documentElement.style.removeProperty('--glow-text');
    }

    // 通知主进程更新窗口背景色（避免白闪）
    try {
      window.api.send('update-bg-color', colors.bg);
    } catch(e) {}
  }

  // 初始化：从主进程拉取当前设置并应用
  function initTheme() {
    // 先应用默认主题，避免白闪
    applyTheme(DEFAULT_THEME, DEFAULT_MODE);

    // 然后尝试从主进程获取真实设置
    if (typeof window !== 'undefined' && window.api) {
      window.api.invoke('get-theme-setting').then(setting => {
        if (setting) {
          applyTheme(setting.theme, setting.mode);
        }
      }).catch(() => {});

      // 监听其他窗口触发的主题变更
      window.api.on('theme-changed', (data) => {
        applyTheme(data.theme || data.id, data.mode);
      });
    }
  }

  // 获取当前主题信息
  function getCurrentThemeInfo() {
    return {
      id: currentThemeId,
      mode: currentMode,
      theme: findTheme(currentThemeId)
    };
  }

  // 暴露到全局
  window.ThemeEngine = {
    applyTheme,
    initTheme,
    getCurrentThemeInfo,
    findTheme
  };
})();
