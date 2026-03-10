import { ref } from "vue";

/** localStorage 鍵名 */
const APPEARANCE_STATE_KEY = "live2d:appearance-enabled";

/**
 * 外貌管理 composable
 * 負責外貌文件的加載、應用、移除、狀態持久化
 */
export function useAppearance() {
  // 所有可用外貌文件列表（与 public/models/luna 下文件名保持完全一致）
  const APPEARANCE_FILES = [
    "后发-右小啾啾隐藏.exp3.json",
    "后发-长发隐藏.exp3.json",
    "后发-左小啾啾隐藏.exp3.json",
    "肩发-缩小~隐藏.exp3.json",
    "肩发-位置收拢.exp3.json",
    "脸-绷带-血隐藏.exp3.json",
    "脸-绷带和血一起隐藏.exp3.json",
    "帽子隐藏.exp3.json",
    "前发-去掉半透.exp3.json",
    "身-毛衣.exp3.json",
    "身-腿绷带血隐藏.exp3.json",
    "身-腿绷带隐藏.exp3.json",
    "身-围巾.exp3.json",
    "手-抱猫.exp3.json",
    "手-手提包隐藏.exp3.json",
    "兽耳-隐藏.exp3.json",
    "兽尾-隐藏1.exp3.json",
    "兽尾-隐藏2.exp3.json",
    "眼-眼镜.exp3.json",
    "眼-右眼粉瞳色.exp3.json",
    "眼-左眼粉瞳色.exp3.json",
    "眼影隐藏.exp3.json",
  ];

  // 各文件启用状态（响应式）
  const appearanceEnabled = ref({});

  // 已应用文件的元数据，用于回退（非响应式，纯数据）
  const appearanceAppliedMeta = {};

  // 轻提示文本
  const appearanceHint = ref("");
  let appearanceHintTimer = null;

  /** 显示操作轻提示 */
  function showAppearanceHint(text, duration = 1500) {
    appearanceHint.value = text;
    clearTimeout(appearanceHintTimer);
    appearanceHintTimer = setTimeout(() => {
      appearanceHint.value = "";
    }, duration);
  }

  /** 将文件名转换为显示名称（界面使用简体） */
  function displayAppearanceName(file) {
    return file.replace(/\.exp3\.json$/i, "");
  }

  /** 从 localStorage 读取已保存的启用状态 */
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

  /** 将当前启用状态持久化到 localStorage */
  function saveAppearanceState() {
    const obj = {};
    for (const f of APPEARANCE_FILES) {
      obj[f] = !!appearanceEnabled.value[f];
    }
    localStorage.setItem(APPEARANCE_STATE_KEY, JSON.stringify(obj));
  }

  /**
   * 应用单个外貌文件
   * 会记录原始参数值，以便后续移除时回退
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
   * 移除单个外貌文件，根据 meta 回退参数
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
   * 并行应用所有已启用的外貌文件
   * 使用 Promise.all 提升加载速度
   */
  async function applyAllEnabled(core) {
    const tasks = APPEARANCE_FILES
      .filter((f) => appearanceEnabled.value[f])
      .map((f) => applyAppearanceFile(f, core));
    await Promise.all(tasks);
    showAppearanceHint("已应用当前外貌设置");
  }

  /** 禁用所有外貌并清除状态 */
  async function disableAll(core) {
    for (const f of APPEARANCE_FILES.slice()) {
      if (appearanceAppliedMeta[f]) {
        removeAppearanceFile(f, core);
      }
      appearanceEnabled.value[f] = false;
    }
    saveAppearanceState();
    showAppearanceHint("已恢复默认外貌");
  }

  /** 切换单个外貌文件（由 checkbox 触发） */
  async function onAppearanceToggle(file, core) {
    saveAppearanceState();
    const name = displayAppearanceName(file);
    if (appearanceEnabled.value[file]) {
      await applyAppearanceFile(file, core);
      showAppearanceHint(`✓ 已启用 ${name}`);
    } else {
      removeAppearanceFile(file, core);
      showAppearanceHint(`✕ 已关闭 ${name}`);
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
