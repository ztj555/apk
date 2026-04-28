/**
 * AutoDial PC端 - 主题数据定义
 * 11套主题，每套包含 dark/light 两套完整配色
 */

const THEME_DATA = [
  // ==================== 1. 暗金 Dark Gold（当前默认） ====================
  {
    id: 'dark-gold',
    name: '暗金',
    nameEn: 'Dark Gold',
    category: 'tech',
    keywords: ['高贵', '经典', '质感'],
    defaultMode: 'dark',
    style: {
      radiusSm: '5px',
      radiusMd: '10px',
      radiusLg: '24px',
      shadow: 'none',
      fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif",
      gradientGreen: 'linear-gradient(135deg, #27AE60, #1E8449)',
      gradientRed: 'linear-gradient(135deg, #C0392B, #96281B)',
      glowText: 'none'
    },
    dark: {
      gold: '#C9A84C', goldLight: '#F0C040', goldDark: '#8B6914',
      bg: '#111318', bg2: '#1A1D24', bg3: '#22262F',
      text: '#E8DCC8', text2: '#A09070',
      green: '#2ECC71', red: '#E74C3C'
    },
    light: {
      gold: '#B8860B', goldLight: '#E6A800', goldDark: '#7A5C12',
      bg: '#FAF6ED', bg2: '#FFFFFF', bg3: '#F0EBE0',
      text: '#2C2416', text2: '#7A6B52',
      green: '#27AE60', red: '#C0392B'
    }
  },

  // ==================== 2. 冰蓝冷峻 Cyber Frost ====================
  {
    id: 'cyber-frost',
    name: '冰蓝冷峻',
    nameEn: 'Cyber Frost',
    category: 'tech',
    keywords: ['冷峻', '科技感', '专业'],
    defaultMode: 'dark',
    style: {
      radiusSm: '4px',
      radiusMd: '8px',
      radiusLg: '20px',
      shadow: '0 2px 16px rgba(0,188,212,0.15)',
      fontFamily: "'Consolas', 'Microsoft YaHei', sans-serif",
      gradientGreen: 'linear-gradient(135deg, #00E676, #00C853)',
      gradientRed: 'linear-gradient(135deg, #FF5252, #D32F2F)',
      glowText: 'none'
    },
    dark: {
      gold: '#00BCD4', goldLight: '#4DD0E1', goldDark: '#006064',
      bg: '#0A1628', bg2: '#122A45', bg3: '#1A3A5C',
      text: '#E0F0FF', text2: '#7BA3C4',
      green: '#00E676', red: '#FF5252'
    },
    light: {
      gold: '#0097A7', goldLight: '#00ACC1', goldDark: '#00838F',
      bg: '#E8F4FC', bg2: '#FFFFFF', bg3: '#D0E8F5',
      text: '#1A3A5C', text2: '#5A8AAF',
      green: '#00C853', red: '#D32F2F'
    }
  },

  // ==================== 3. 极简白 Minimalist ====================
  {
    id: 'minimalist',
    name: '极简白',
    nameEn: 'Minimalist',
    category: 'comfort',
    keywords: ['极简', '干净', '护眼'],
    defaultMode: 'light',
    style: {
      radiusSm: '2px',
      radiusMd: '4px',
      radiusLg: '12px',
      shadow: 'none',
      fontFamily: "-apple-system, 'Segoe UI', sans-serif",
      gradientGreen: 'linear-gradient(135deg, #4CAF50, #43A047)',
      gradientRed: 'linear-gradient(135deg, #EF5350, #E53935)',
      glowText: 'none'
    },
    dark: {
      gold: '#555555', goldLight: '#777777', goldDark: '#888888',
      bg: '#1E1E1E', bg2: '#2D2D2D', bg3: '#3D3D3D',
      text: '#E8E8E8', text2: '#999999',
      green: '#4CAF50', red: '#EF5350'
    },
    light: {
      gold: '#333333', goldLight: '#555555', goldDark: '#666666',
      bg: '#FFFFFF', bg2: '#FAFAFA', bg3: '#F0F0F0',
      text: '#1A1A1A', text2: '#888888',
      green: '#43A047', red: '#E53935'
    }
  },

  // ==================== 4. 毛玻璃 Glassmorphism ====================
  {
    id: 'glassmorphism',
    name: '毛玻璃',
    nameEn: 'Glassmorphism',
    category: 'tech',
    keywords: ['玻璃', '半透明', '现代感'],
    defaultMode: 'dark',
    style: {
      radiusSm: '12px',
      radiusMd: '18px',
      radiusLg: '30px',
      shadow: '0 8px 32px rgba(0,0,0,0.3)',
      fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif",
      gradientGreen: 'linear-gradient(135deg, #34D399, #10B981)',
      gradientRed: 'linear-gradient(135deg, #F87171, #EF4444)',
      glowText: 'none',
      backdropFilter: 'blur(20px)'
    },
    dark: {
      gold: '#A78BFA', goldLight: '#C4B5FD', goldDark: '#7C3AED',
      bg: '#0F0F19', bg2: 'rgba(30,30,50,0.65)', bg3: 'rgba(45,45,70,0.5)',
      text: '#F0EEFF', text2: '#A099CC',
      green: '#34D399', red: '#F87171'
    },
    light: {
      gold: '#8B5CF6', goldLight: '#A78BFA', goldDark: '#6D28D9',
      bg: '#E8E0F8', bg2: 'rgba(255,255,255,0.55)', bg3: 'rgba(240,240,250,0.45)',
      text: '#2D2640', text2: '#6B6190',
      green: '#10B981', red: '#EF4444'
    }
  },

  // ==================== 5. 活力橙 Energetic Orange ====================
  {
    id: 'energetic-orange',
    name: '活力橙',
    nameEn: 'Energetic Orange',
    category: 'creative',
    keywords: ['活泼', '温暖', '有活力'],
    defaultMode: 'dark',
    style: {
      radiusSm: '6px',
      radiusMd: '12px',
      radiusLg: '26px',
      shadow: '0 4px 20px rgba(255,152,0,0.2)',
      fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif",
      gradientGreen: 'linear-gradient(135deg, #66BB6A, #4CAF50)',
      gradientRed: 'linear-gradient(135deg, #EF5350, #E53935)',
      glowText: 'none'
    },
    dark: {
      gold: '#FF9800', goldLight: '#FFB74D', goldDark: '#E65100',
      bg: '#1A1510', bg2: '#2A2018', bg3: '#3A2D20',
      text: '#FFF5E6', text2: '#B08D60',
      green: '#66BB6A', red: '#EF5350'
    },
    light: {
      gold: '#F57C00', goldLight: '#FFA726', goldDark: '#EF6C00',
      bg: '#FFF8F0', bg2: '#FFFFFF', bg3: '#FFEFD5',
      text: '#3D2B1A', text2: '#A07850',
      green: '#4CAF50', red: '#E53935'
    }
  },

  // ==================== 6. 圆润糖果 Rounded Candy ====================
  {
    id: 'rounded-candy',
    name: '圆润糖果',
    nameEn: 'Rounded Candy',
    category: 'comfort',
    keywords: ['圆润', '可爱', '柔和'],
    defaultMode: 'light',
    style: {
      radiusSm: '14px',
      radiusMd: '20px',
      radiusLg: '40px',
      shadow: '0 4px 16px rgba(255,107,157,0.15)',
      fontFamily: "'Nunito', 'Microsoft YaHei', sans-serif",
      gradientGreen: 'linear-gradient(135deg, #69F0AE, #00E676)',
      gradientRed: 'linear-gradient(135deg, #FF8A80, #FF5252)',
      glowText: 'none'
    },
    dark: {
      gold: '#FF6B9D', goldLight: '#FF8FB1', goldDark: '#C2185B',
      bg: '#1A1020', bg2: '#2A1830', bg3: '#3A2040',
      text: '#FFE8F0', text2: '#A07090',
      green: '#69F0AE', red: '#FF8A80'
    },
    light: {
      gold: '#EC407A', goldLight: '#F06292', goldDark: '#D81B60',
      bg: '#FFF0F5', bg2: '#FFFFFF', bg3: '#FFE4EC',
      text: '#3D1E2D', text2: '#A06080',
      green: '#00E676', red: '#FF5252'
    }
  },

  // ==================== 7. 深空紫 Deep Space Purple ====================
  {
    id: 'deep-space',
    name: '深空紫',
    nameEn: 'Deep Space',
    category: 'tech',
    keywords: ['深邃', '神秘', '高端'],
    defaultMode: 'dark',
    style: {
      radiusSm: '6px',
      radiusMd: '12px',
      radiusLg: '22px',
      shadow: '0 4px 24px rgba(187,134,252,0.12)',
      fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif",
      gradientGreen: 'linear-gradient(135deg, #00E676, #00C853)',
      gradientRed: 'linear-gradient(135deg, #FF5252, #D32F2F)',
      glowText: 'none'
    },
    dark: {
      gold: '#BB86FC', goldLight: '#DA98FF', goldDark: '#7B1FA2',
      bg: '#0D0A18', bg2: '#18142E', bg3: '#241E42',
      text: '#E8DEFF', text2: '#9575CD',
      green: '#00E676', red: '#FF5252'
    },
    light: {
      gold: '#9C27B0', goldLight: '#AB47BC', goldDark: '#8E24AA',
      bg: '#F5F0FF', bg2: '#FFFFFF', bg3: '#EDE5F8',
      text: '#2D1E42', text2: '#7E57C2',
      green: '#00C853', red: '#D32F2F'
    }
  },

  // ==================== 8. 森林绿 Forest Green ====================
  {
    id: 'forest-green',
    name: '森林绿',
    nameEn: 'Forest Green',
    category: 'comfort',
    keywords: ['自然', '安静', '护眼'],
    defaultMode: 'dark',
    style: {
      radiusSm: '6px',
      radiusMd: '10px',
      radiusLg: '22px',
      shadow: '0 2px 12px rgba(129,199,132,0.1)',
      fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif",
      gradientGreen: 'linear-gradient(135deg, #69F0AE, #00E676)',
      gradientRed: 'linear-gradient(135deg, #FF8A80, #E53935)',
      glowText: 'none'
    },
    dark: {
      gold: '#81C784', goldLight: '#A5D6A7', goldDark: '#388E3C',
      bg: '#0E1810', bg2: '#182818', bg3: '#223822',
      text: '#E0F0E0', text2: '#7AA07A',
      green: '#69F0AE', red: '#FF8A80'
    },
    light: {
      gold: '#4CAF50', goldLight: '#66BB6A', goldDark: '#43A047',
      bg: '#F0F8F0', bg2: '#FFFFFF', bg3: '#E8F4E8',
      text: '#1E3A1E', text2: '#5E8A5E',
      green: '#00E676', red: '#E53935'
    }
  },

  // ==================== 9. 赛博朋克 Cyberpunk Neon ====================
  {
    id: 'cyberpunk',
    name: '赛博朋克',
    nameEn: 'Cyberpunk Neon',
    category: 'creative',
    keywords: ['霓虹', '酷炫', '未来'],
    defaultMode: 'dark',
    style: {
      radiusSm: '3px',
      radiusMd: '6px',
      radiusLg: '16px',
      shadow: '0 0 20px rgba(0,255,255,0.3)',
      fontFamily: "'Consolas', 'Microsoft YaHei', monospace",
      gradientGreen: 'linear-gradient(135deg, #39FF14, #00E676)',
      gradientRed: 'linear-gradient(135deg, #FF0039, #FF1744)',
      glowText: '0 0 10px currentColor'
    },
    dark: {
      gold: '#00FFFF', goldLight: '#80FFFF', goldDark: '#008B8B',
      bg: '#0A0010', bg2: '#150022', bg3: '#220035',
      text: '#F0F0FF', text2: '#8866CC',
      green: '#39FF14', red: '#FF0039'
    },
    light: {
      gold: '#00BCD4', goldLight: '#4DD0E1', goldDark: '#0097A7',
      bg: '#F0FAFF', bg2: '#FFFFFF', bg3: '#E0F5FF',
      text: '#1A1A2E', text2: '#665599',
      green: '#00E676', red: '#FF1744'
    }
  },

  // ==================== 10. 暖光米色 Warm Cream ====================
  {
    id: 'warm-cream',
    name: '暖光米色',
    nameEn: 'Warm Cream',
    category: 'comfort',
    keywords: ['温暖', '舒适', '复古'],
    defaultMode: 'light',
    style: {
      radiusSm: '8px',
      radiusMd: '14px',
      radiusLg: '28px',
      shadow: '0 2px 16px rgba(212,165,116,0.12)',
      fontFamily: "'Georgia', 'Palatino', 'Microsoft YaHei', serif",
      gradientGreen: 'linear-gradient(135deg, #81C784, #4CAF50)',
      gradientRed: 'linear-gradient(135deg, #E57373, #EF5350)',
      glowText: 'none'
    },
    dark: {
      gold: '#D4A574', goldLight: '#E8C49A', goldDark: '#A67C52',
      bg: '#1A1612', bg2: '#2A2218', bg3: '#3A2E20',
      text: '#F0E6D8', text2: '#A09080',
      green: '#81C784', red: '#E57373'
    },
    light: {
      gold: '#C4956A', goldLight: '#D4A574', goldDark: '#967048',
      bg: '#FFF9F0', bg2: '#FFFFFF', bg3: '#F5EDE0',
      text: '#3D3020', text2: '#908068',
      green: '#4CAF50', red: '#EF5350'
    }
  },

  // ==================== 11. 海洋蓝 Ocean Blue ====================
  {
    id: 'ocean-blue',
    name: '海洋蓝',
    nameEn: 'Ocean Blue',
    category: 'comfort',
    keywords: ['清新', '开阔', '平静'],
    defaultMode: 'light',
    style: {
      radiusSm: '6px',
      radiusMd: '10px',
      radiusLg: '24px',
      shadow: '0 4px 20px rgba(66,165,245,0.12)',
      fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif",
      gradientGreen: 'linear-gradient(135deg, #00E676, #00C853)',
      gradientRed: 'linear-gradient(135deg, #FF5252, #E53935)',
      glowText: 'none'
    },
    dark: {
      gold: '#42A5F5', goldLight: '#64B5F6', goldDark: '#1565C0',
      bg: '#0B1424', bg2: '#152238', bg3: '#1E3050',
      text: '#E0ECFF', text2: '#7890B8',
      green: '#00E676', red: '#FF5252'
    },
    light: {
      gold: '#1E88E5', goldLight: '#42A5F5', goldDark: '#1976D2',
      bg: '#F0F6FF', bg2: '#FFFFFF', bg3: '#E3ECF8',
      text: '#152238', text2: '#5C7898',
      green: '#00C853', red: '#E53935'
    }
  }
];

// 默认主题
const DEFAULT_THEME = 'dark-gold';
const DEFAULT_MODE = 'dark';
