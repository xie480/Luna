import { ref } from "vue";

/** localStorage 鍵名 */
const APPEARANCE_STATE_KEY = "live2d:appearance-enabled";

/**
 * 外貌管理 composable
 * 負責外貌文件的加載、應用、移除、狀態持久化
 */
export function useAppearance() {
  // 所有可用外貌文件列表
  const APPEARANCE_FILES = [
    "後發-右小啾啾隱藏.exp3.json",
    "後發-長發隱藏.exp3.json",
    "後發-左小啾啾隱藏.exp3.json",
    "肩發-縮小~隱藏.exp3.json",
    "肩發-位置收攏.exp3.json",
    "臉-繃帶-血隱藏.exp3.json",
    "臉-繃帶和血一起隱藏.exp3.json",
    "帽子隱藏.exp3.json",
    "前發-去掉半透.exp3.json",
    "身-毛衣.exp3.json",
    "身-腿綁帶血隱藏.exp3.json",
    "身-腿綁帶隱藏.exp3.json",
    "身-圍巾.exp3.json",
    "手-抱貓.exp3.json",
    "手-手提包隱藏.exp3.json",
    "獸耳-隱藏.exp3.json",
    "獸尾-隱藏1.exp3.json",
    "獸尾-隱藏2.exp3.json",
    "眼-眼鏡.exp3.json",
    "眼-右眼粉瞳色.exp3.json",
    "眼-左眼粉瞳色.exp3.json",
    "眼影隱藏.exp3.json",
  ];

  // 各文件啟用狀態（響應式）
  const appearanceEnabled = ref({});

  // 已應用文件的元數據，用於回退（非響應式，純數據）
  const appearanceAppliedMeta = {};

  // 輕提示文本
  const appearanceHint = ref("");
  let appearanceHintTimer = null;

  /** 顯示操作輕提示 */
  function showAppearanceHint(text, duration = 1500) {
    appearanceHint.value = text;
    clearTimeout(appearanceHintTimer);
    appearanceHintTimer = setTimeout(() => {
      appearanceHint.value = "";
    }, duration);
  }

  /** 將文件名轉換為顯示名稱 */
  function displayAppearanceName(file) {
    file = file.replace(/\.exp3\.json$/i, "");
    if (file === "帽子隱藏") file = "帽子";
    return file;
  }

  /** 從 localStorage 讀取已保存的啟用狀態 */
  function loadAppearanceState() {
    const raw = localStorage.getItem(APPEARANCE_STATE_KEY);
    let saved = {};
    try {
      saved = raw ? JSON.parse(raw) : {};
    } catch {}
    APPEARANCE_FILES.forEach((f) => {
      appearanceEnabled.value[f] = !!saved[f];
    });
  }

  /** 將當前啟用狀態持久化到 localStorage */
  function saveAppearanceState() {
    const obj = {};
    for (const f of APPEARANCE_FILES) {
      obj[f] = !!appearanceEnabled.value[f];
    }
    localStorage.setItem(APPEARANCE_STATE_KEY, JSON.stringify(obj));
  }

  /**
   * 應用單個外貌文件
   * 會記錄原始參數值，以便後續移除時回退
   */
  async function applyAppearanceFile(file, core) {
    if (!core) return;
    try {
      const res = await fetch(`/models/luna/${encodeURIComponent(file)}`);
      if (!res.ok) throw new Error("fetch fail");
      const expJson = await res.json();

      const meta = [];
      (expJson.Parameters || []).forEach(({ Id, Value, Blend }) => {
        try {
          const old = core.getParameterValueById(Id) || 0;
          if (Blend === "Add") {
            core.setParameterValueById(Id, old + Value);
            meta.push({ Id, Blend, value: Value });
          } else {
            core.setParameterValueById(Id, Value);
            meta.push({ Id, Blend, previous: old, value: Value });
          }
        } catch {}
      });

      appearanceAppliedMeta[file] = meta;
    } catch (e) {
      console.warn("[Appearance] applyAppearanceFile error:", file, e);
    }
  }

  /**
   * 移除單個外貌文件，根據 meta 回退參數
   */
  function removeAppearanceFile(file, core) {
    if (!core) return;
    const meta = appearanceAppliedMeta[file];
    if (!meta) return;

    meta.forEach((m) => {
      try {
        if (m.Blend === "Add") {
          const cur = core.getParameterValueById(m.Id) || 0;
          core.setParameterValueById(m.Id, cur - (m.value || 0));
        } else {
          core.setParameterValueById(m.Id, m.previous || 0);
        }
      } catch {}
    });

    delete appearanceAppliedMeta[file];
  }

  /**
   * 並行應用所有已啟用的外貌文件
   * 使用 Promise.all 提升加載速度
   */
  async function applyAllEnabled(core) {
    const tasks = APPEARANCE_FILES
      .filter((f) => appearanceEnabled.value[f])
      .map((f) => applyAppearanceFile(f, core));
    await Promise.all(tasks);
    showAppearanceHint("已應用當前外貌設置");
  }

  /** 禁用所有外貌並清除狀態 */
  async function disableAll(core) {
    for (const f of APPEARANCE_FILES.slice()) {
      if (appearanceAppliedMeta[f]) {
        removeAppearanceFile(f, core);
      }
      appearanceEnabled.value[f] = false;
    }
    saveAppearanceState();
    showAppearanceHint("已恢復默認外貌");
  }

  /** 切換單個外貌文件（由 checkbox 觸發） */
  async function onAppearanceToggle(file, core) {
    saveAppearanceState();
    const name = displayAppearanceName(file);
    if (appearanceEnabled.value[file]) {
      await applyAppearanceFile(file, core);
      showAppearanceHint(`✓ 已啟用 ${name}`);
    } else {
      removeAppearanceFile(file, core);
      showAppearanceHint(`✕ 已關閉 ${name}`);
    }
  }

  return {
    APPEARANCE_FILES,
    appearanceEnabled,
    appearanceAppliedMeta,
    appearanceHint,
    showAppearanceHint,
    displayAppearanceName,
    loadAppearanceState,
    saveAppearanceState,
    applyAppearanceFile,
    removeAppearanceFile,
    applyAllEnabled,
    disableAll,
    onAppearanceToggle,
  };
}
