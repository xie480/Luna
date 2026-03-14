import { ref } from "vue";

const THEME_KEY = "luna:theme-settings";

// 定義主題配置
const THEMES = {
  cyberpunk: {
    name: "賽博霓虹 (默認)",
    colors: {
      "--primary": "#00ffc8",
      "--primary-dim": "rgba(0,255,200,0.12)",
      "--bg-panel": "rgba(5,10,19,0.95)",
      "--text-main": "#e8fff8",
      "--border": "rgba(0,255,200,0.3)"
    }
  },
  obsidian: {
    name: "黑曜石",
    colors: {
      "--primary": "#ffffff",
      "--primary-dim": "rgba(255,255,255,0.1)",
      "--bg-panel": "rgba(0,0,0,0.95)",
      "--text-main": "#f0f0f0",
      "--border": "rgba(255,255,255,0.2)"
    }
  },
  sakura: {
    name: "櫻花粉",
    colors: {
      "--primary": "#ff9eb5",
      "--primary-dim": "rgba(255,158,181,0.15)",
      "--bg-panel": "rgba(25,10,15,0.95)",
      "--text-main": "#fff0f5",
      "--border": "rgba(255,158,181,0.4)"
    }
  }
};

const currentTheme = ref("cyberpunk");

export function useTheme() {
  
  function loadTheme() {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved && THEMES[saved]) {
      currentTheme.value = saved;
      applyThemeToDom(saved);
    } else {
      applyThemeToDom("cyberpunk");
    }
  }

  function applyTheme(themeKey) {
    if (!THEMES[themeKey]) return;
    currentTheme.value = themeKey;
    localStorage.setItem(THEME_KEY, themeKey);
    applyThemeToDom(themeKey);
  }

  function applyThemeToDom(key) {
    const colors = THEMES[key].colors;
    const root = document.documentElement;
    for (const [cssVar, value] of Object.entries(colors)) {
      root.style.setProperty(cssVar, value);
    }
  }

  return {
    THEMES,
    currentTheme,
    loadTheme,
    applyTheme
  };
}
