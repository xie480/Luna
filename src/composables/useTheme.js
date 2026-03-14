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
      "--bg-sidebar": "rgba(2,5,10,0.95)",
      "--text-main": "#e8fff8",
      "--text-dim": "rgba(232,255,248,0.6)",
      "--border": "rgba(0,255,200,0.3)",
      "--accent": "#00ffc8",
      "--hover": "rgba(0,255,200,0.2)"
    }
  },
  obsidian: {
    name: "黑曜石 (極簡黑)",
    colors: {
      "--primary": "#ffffff",
      "--primary-dim": "rgba(255,255,255,0.1)",
      "--bg-panel": "rgba(15,15,15,0.95)",
      "--bg-sidebar": "rgba(5,5,5,0.95)",
      "--text-main": "#f0f0f0",
      "--text-dim": "#888888",
      "--border": "rgba(255,255,255,0.2)",
      "--accent": "#ffffff",
      "--hover": "rgba(255,255,255,0.15)"
    }
  },
  sakura: {
    name: "櫻花粉 (柔和)",
    colors: {
      "--primary": "#ff9eb5",
      "--primary-dim": "rgba(255,158,181,0.15)",
      "--bg-panel": "rgba(40,20,25,0.95)",
      "--bg-sidebar": "rgba(30,15,20,0.95)",
      "--text-main": "#fff0f5",
      "--text-dim": "rgba(255,240,245,0.7)",
      "--border": "rgba(255,158,181,0.4)",
      "--accent": "#ffb7c5",
      "--hover": "rgba(255,158,181,0.2)"
    }
  },
  ocean: {
    name: "深海藍 (靜謐)",
    colors: {
      "--primary": "#4facfe",
      "--primary-dim": "rgba(79,172,254,0.15)",
      "--bg-panel": "rgba(5,20,40,0.95)",
      "--bg-sidebar": "rgba(2,10,25,0.95)",
      "--text-main": "#e0f7fa",
      "--text-dim": "#81d4fa",
      "--border": "rgba(79,172,254,0.4)",
      "--accent": "#00f2fe",
      "--hover": "rgba(79,172,254,0.2)"
    }
  },
  forest: {
    name: "迷霧森林 (自然)",
    colors: {
      "--primary": "#56ab2f",
      "--primary-dim": "rgba(86,171,47,0.15)",
      "--bg-panel": "rgba(10,25,10,0.95)",
      "--bg-sidebar": "rgba(5,15,5,0.95)",
      "--text-main": "#e8f5e9",
      "--text-dim": "#a5d6a7",
      "--border": "rgba(86,171,47,0.4)",
      "--accent": "#a8e063",
      "--hover": "rgba(86,171,47,0.2)"
    }
  },
  violet: {
    name: "虛空紫 (神秘)",
    colors: {
      "--primary": "#9d50bb",
      "--primary-dim": "rgba(157,80,187,0.15)",
      "--bg-panel": "rgba(20,5,25,0.95)",
      "--bg-sidebar": "rgba(15,2,20,0.95)",
      "--text-main": "#f3e5f5",
      "--text-dim": "#ce93d8",
      "--border": "rgba(157,80,187,0.4)",
      "--accent": "#6e48aa",
      "--hover": "rgba(157,80,187,0.2)"
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
