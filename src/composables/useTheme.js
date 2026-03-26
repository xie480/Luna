import { ref } from "vue";

const THEME_KEY = "luna:theme-settings";
const FX_KEY = "luna:ui-fx-enabled";

// 定义主题配置（扩展全局变量，确保所有面板可跟随）
const THEMES = {
  cyberpunk: {
    name: "赛博霓虹 (默认)",
    colors: {
      "--primary": "#00ffc8",
      "--primary-2": "#00aaff",
      "--primary-dim": "rgba(0,255,200,0.12)",
      "--bg-panel": "linear-gradient(145deg, rgba(8,14,26,0.95), rgba(5,10,19,0.95))",
      "--bg-panel-soft": "rgba(255,255,255,0.03)",
      "--bg-sidebar": "rgba(2,5,10,0.95)",
      "--text-main": "#e8fff8",
      "--text-dim": "rgba(232,255,248,0.62)",
      "--border": "rgba(0,255,200,0.3)",
      "--accent": "#00ffc8",
      "--hover": "rgba(0,255,200,0.12)",
      "--shadow-panel": "0 20px 60px rgba(0,0,0,0.75), 0 0 0 1px rgba(255,255,255,0.03) inset",
      "--glow-primary": "0 0 14px rgba(0,255,200,0.2)"
    }
  },
  obsidian: {
    name: "黑曜石 (极简黑)",
    colors: {
      "--primary": "#ffffff",
      "--primary-2": "#bdbdbd",
      "--primary-dim": "rgba(255,255,255,0.1)",
      "--bg-panel": "linear-gradient(145deg, rgba(18,18,18,0.96), rgba(12,12,12,0.96))",
      "--bg-panel-soft": "rgba(255,255,255,0.025)",
      "--bg-sidebar": "rgba(8,8,8,0.95)",
      "--text-main": "#f2f2f2",
      "--text-dim": "rgba(220,220,220,0.55)",
      "--border": "rgba(255,255,255,0.22)",
      "--accent": "#ffffff",
      "--hover": "rgba(255,255,255,0.1)",
      "--shadow-panel": "0 18px 48px rgba(0,0,0,0.85), 0 0 0 1px rgba(255,255,255,0.02) inset",
      "--glow-primary": "0 0 10px rgba(255,255,255,0.16)"
    }
  },
  solar: {
    name: "日光白 (亮色)",
    colors: {
      "--primary": "#ff9800",
      "--primary-2": "#ff5722",
      "--primary-dim": "rgba(255,152,0,0.15)",
      "--bg-panel": "linear-gradient(145deg, rgba(248,248,248,0.96), rgba(235,235,235,0.96))",
      "--bg-panel-soft": "rgba(0,0,0,0.03)",
      "--bg-sidebar": "rgba(228,228,228,0.95)",
      "--text-main": "#2f2f2f",
      "--text-dim": "rgba(60,60,60,0.66)",
      "--border": "rgba(0,0,0,0.15)",
      "--accent": "#ff5722",
      "--hover": "rgba(0,0,0,0.06)",
      "--shadow-panel": "0 16px 42px rgba(0,0,0,0.2), 0 0 0 1px rgba(255,255,255,0.45) inset",
      "--glow-primary": "0 0 10px rgba(255,152,0,0.2)"
    }
  },
  crimson: {
    name: "猩红月 (暗红)",
    colors: {
      "--primary": "#ff0033",
      "--primary-2": "#ff6a00",
      "--primary-dim": "rgba(255,0,51,0.15)",
      "--bg-panel": "linear-gradient(145deg, rgba(26,5,10,0.96), rgba(16,2,6,0.96))",
      "--bg-panel-soft": "rgba(255,255,255,0.03)",
      "--bg-sidebar": "rgba(12,0,3,0.95)",
      "--text-main": "#ffd7df",
      "--text-dim": "rgba(255,170,180,0.62)",
      "--border": "rgba(255,0,51,0.42)",
      "--accent": "#ff3d68",
      "--hover": "rgba(255,0,51,0.15)",
      "--shadow-panel": "0 20px 62px rgba(0,0,0,0.82), 0 0 16px rgba(255,0,51,0.12)",
      "--glow-primary": "0 0 14px rgba(255,0,51,0.22)"
    }
  },
  gold: {
    name: "流金岁月 (奢华)",
    colors: {
      "--primary": "#ffd700",
      "--primary-2": "#ffb300",
      "--primary-dim": "rgba(255,215,0,0.15)",
      "--bg-panel": "linear-gradient(145deg, rgba(32,26,10,0.96), rgba(22,18,8,0.96))",
      "--bg-panel-soft": "rgba(255,255,255,0.03)",
      "--bg-sidebar": "rgba(18,14,5,0.95)",
      "--text-main": "#fff8e1",
      "--text-dim": "rgba(255,231,169,0.62)",
      "--border": "rgba(255,215,0,0.4)",
      "--accent": "#ffb300",
      "--hover": "rgba(255,215,0,0.12)",
      "--shadow-panel": "0 20px 56px rgba(0,0,0,0.78), 0 0 16px rgba(255,215,0,0.1)",
      "--glow-primary": "0 0 14px rgba(255,215,0,0.2)"
    }
  },
  hacker: {
    name: "矩阵终端 (复古)",
    colors: {
      "--primary": "#00ff00",
      "--primary-2": "#8dff8d",
      "--primary-dim": "rgba(0,255,0,0.15)",
      "--bg-panel": "linear-gradient(145deg, rgba(0,0,0,0.98), rgba(0,12,0,0.98))",
      "--bg-panel-soft": "rgba(0,255,0,0.03)",
      "--bg-sidebar": "rgba(0,18,0,0.98)",
      "--text-main": "#c6ffc6",
      "--text-dim": "rgba(0,255,0,0.58)",
      "--border": "rgba(0,255,0,0.65)",
      "--accent": "#00ff00",
      "--hover": "rgba(0,255,0,0.14)",
      "--shadow-panel": "0 18px 48px rgba(0,0,0,0.88), 0 0 16px rgba(0,255,0,0.14)",
      "--glow-primary": "0 0 14px rgba(0,255,0,0.24)"
    }
  },
  sakura: {
    name: "樱花粉 (柔和)",
    colors: {
      "--primary": "#ff9eb5",
      "--primary-2": "#d17cff",
      "--primary-dim": "rgba(255,158,181,0.15)",
      "--bg-panel": "linear-gradient(145deg, rgba(42,20,28,0.96), rgba(28,14,20,0.96))",
      "--bg-panel-soft": "rgba(255,255,255,0.03)",
      "--bg-sidebar": "rgba(30,14,22,0.95)",
      "--text-main": "#fff1f6",
      "--text-dim": "rgba(255,220,232,0.62)",
      "--border": "rgba(255,158,181,0.4)",
      "--accent": "#ffb7c5",
      "--hover": "rgba(255,158,181,0.14)",
      "--shadow-panel": "0 20px 58px rgba(0,0,0,0.8), 0 0 16px rgba(255,158,181,0.1)",
      "--glow-primary": "0 0 14px rgba(255,158,181,0.22)"
    }
  },
  ocean: {
    name: "深海蓝 (静谧)",
    colors: {
      "--primary": "#4facfe",
      "--primary-2": "#00f2fe",
      "--primary-dim": "rgba(79,172,254,0.15)",
      "--bg-panel": "linear-gradient(145deg, rgba(6,20,40,0.96), rgba(3,10,24,0.96))",
      "--bg-panel-soft": "rgba(255,255,255,0.03)",
      "--bg-sidebar": "rgba(2,10,25,0.95)",
      "--text-main": "#e8f9ff",
      "--text-dim": "rgba(170,220,245,0.62)",
      "--border": "rgba(79,172,254,0.4)",
      "--accent": "#00f2fe",
      "--hover": "rgba(79,172,254,0.14)",
      "--shadow-panel": "0 20px 58px rgba(0,0,0,0.82), 0 0 16px rgba(79,172,254,0.12)",
      "--glow-primary": "0 0 14px rgba(79,172,254,0.2)"
    }
  },
  forest: {
    name: "迷雾森林 (自然)",
    colors: {
      "--primary": "#56ab2f",
      "--primary-2": "#a8e063",
      "--primary-dim": "rgba(86,171,47,0.15)",
      "--bg-panel": "linear-gradient(145deg, rgba(10,24,10,0.96), rgba(5,14,6,0.96))",
      "--bg-panel-soft": "rgba(255,255,255,0.03)",
      "--bg-sidebar": "rgba(5,15,5,0.95)",
      "--text-main": "#ecffec",
      "--text-dim": "rgba(181,224,174,0.62)",
      "--border": "rgba(86,171,47,0.4)",
      "--accent": "#a8e063",
      "--hover": "rgba(86,171,47,0.14)",
      "--shadow-panel": "0 20px 56px rgba(0,0,0,0.82), 0 0 16px rgba(86,171,47,0.12)",
      "--glow-primary": "0 0 14px rgba(86,171,47,0.22)"
    }
  },
  violet: {
    name: "虚空紫 (神秘)",
    colors: {
      "--primary": "#9d50bb",
      "--primary-2": "#6e48aa",
      "--primary-dim": "rgba(157,80,187,0.15)",
      "--bg-panel": "linear-gradient(145deg, rgba(22,8,30,0.96), rgba(14,4,20,0.96))",
      "--bg-panel-soft": "rgba(255,255,255,0.03)",
      "--bg-sidebar": "rgba(15,2,20,0.95)",
      "--text-main": "#f5ecff",
      "--text-dim": "rgba(214,178,235,0.62)",
      "--border": "rgba(157,80,187,0.4)",
      "--accent": "#c77dff",
      "--hover": "rgba(157,80,187,0.14)",
      "--shadow-panel": "0 20px 56px rgba(0,0,0,0.82), 0 0 16px rgba(157,80,187,0.12)",
      "--glow-primary": "0 0 14px rgba(157,80,187,0.22)"
    }
  }
};

const currentTheme = ref("cyberpunk");
const uiFxEnabled = ref(true);

function applyThemeToDom(key) {
  const colors = THEMES[key].colors;
  const root = document.documentElement;
  for (const [cssVar, value] of Object.entries(colors)) {
    root.style.setProperty(cssVar, value);
  }
}

function applyFxToDom(enabled) {
  const root = document.documentElement;
  if (enabled) {
    root.classList.remove("fx-off");
    root.classList.add("fx-on");
  } else {
    root.classList.remove("fx-on");
    root.classList.add("fx-off");
  }
}

export function useTheme() {
  function loadTheme() {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved && THEMES[saved]) {
      currentTheme.value = saved;
      applyThemeToDom(saved);
    } else {
      currentTheme.value = "cyberpunk";
      applyThemeToDom("cyberpunk");
    }

    const fxSaved = localStorage.getItem(FX_KEY);
    uiFxEnabled.value = fxSaved === null ? true : fxSaved !== "false";
    applyFxToDom(uiFxEnabled.value);
  }

  function applyTheme(themeKey) {
    if (!THEMES[themeKey]) return;
    currentTheme.value = themeKey;
    localStorage.setItem(THEME_KEY, themeKey);
    applyThemeToDom(themeKey);
  }

  function setUiFxEnabled(enabled) {
    const val = !!enabled;
    uiFxEnabled.value = val;
    localStorage.setItem(FX_KEY, String(val));
    applyFxToDom(val);
  }

  return {
    THEMES,
    currentTheme,
    uiFxEnabled,
    loadTheme,
    applyTheme,
    setUiFxEnabled
  };
}
