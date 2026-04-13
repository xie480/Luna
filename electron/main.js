/* 保持你现有文件内容，仅补充查询 IPC handlers */
// main.js
import { app, BrowserWindow, ipcMain, globalShortcut, shell } from "electron";
import path from "path";
import { fileURLToPath } from "url";

// 引入統一的 HTTP 客戶端和 Token 管理
import http, { setAuthToken, getAuthToken } from "../src/main/httpClient.js";
// 引入我們寫好的 chatIpc 以及暴露出來的 startSSE 和 stopSSE
import { registerChatIpc, startSSE, stopSSE } from "../src/main/ipc/chatIpc.js";
// 引入 MCP IPC
import { registerMcpIpc } from "../src/main/ipc/mcpIpc.js";
import { registerPromptIpc } from "../src/main/ipc/promptIpc.js";
import { registerRagIpc } from "../src/main/ipc/ragIpc.js";

// [Fix] 禁用硬件加速，解決透明窗口下 WebGL/Canvas 渲染問題 (模型不可見的關鍵修復)
app.disableHardwareAcceleration();

/* ===== 修复 __dirname ===== */
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// 註冊 chatIpc 中的監聽器 (Startup, Message, Shutdown, SSE)
registerChatIpc();
// 註冊 MCP 相關監聽器
registerMcpIpc();
registerPromptIpc();
registerRagIpc();

/* ===== IPC handlers for History API ===== */
ipcMain.handle("luna.api.chat.history.date", async (_event, yearMonth) => {
  console.log("[History] fetching available dates for", yearMonth);

  if (typeof yearMonth !== "string") {
    throw new TypeError("yearMonth must be string");
  }

  return http
    .get("/luna/api/chat/history/date", {
      params: { ym: yearMonth },
    })
    .catch((err) => {
      throw new Error(err.message);
    });
});

ipcMain.handle("luna.api.chat.history", async (_event, yearMonthDay) => {
  console.log("[History] fetching chat history for", yearMonthDay);
  if (typeof yearMonthDay !== "string") {
    throw new TypeError("yearMonthDay must be string");
  }
  return http
    .get("/luna/api/chat/history", { params: { ymd: yearMonthDay } })
    .catch((err) => {
      throw new Error(err.message);
    });
});

/* ===== 新增：四个分页查询 IPC ===== */
ipcMain.handle("luna.api.query.knowledge-base", async (_event, payload = {}) => {
  return http.post("/luna/api/query/knowledge-base", payload).catch((err) => {
    throw new Error(err.message);
  });
});

ipcMain.handle("luna.api.query.user-preference", async (_event, payload = {}) => {
  return http.post("/luna/api/query/user-preference", payload).catch((err) => {
    throw new Error(err.message);
  });
});

ipcMain.handle("luna.api.query.memory", async (_event, payload = {}) => {
  return http.post("/luna/api/query/memory", payload).catch((err) => {
    throw new Error(err.message);
  });
});

ipcMain.handle("luna.api.query.log", async (_event, payload = {}) => {
  return http.post("/luna/api/query/log", payload).catch((err) => {
    throw new Error(err.message);
  });
});

/* ===== OpenClaw Plan IPC ===== */
ipcMain.handle("luna.api.plan.run", async (_event, payload = {}) => {
  return http.post("/luna/api/plan/run", payload).catch((err) => {
    throw new Error(err.message);
  });
});

ipcMain.handle("luna.api.plan.phase.run", async (_event, payload = {}) => {
  return http.post("/luna/api/plan/phase/run", payload).catch((err) => {
    throw new Error(err.message);
  });
});

ipcMain.handle("luna.api.plan.report.finalize", async (_event, payload = {}) => {
  return http.post("/luna/api/plan/report/finalize", payload).catch((err) => {
    throw new Error(err.message);
  });
});

ipcMain.handle("luna.api.plan.graph", async (_event, planId) => {
  if (!planId || typeof planId !== "string") {
    throw new Error("planId is required");
  }
  return http.get(`/luna/api/plan/graph/${encodeURIComponent(planId)}`).catch((err) => {
    throw new Error(err.message);
  });
});

ipcMain.handle("luna.app.openExternal", async (_event, target) => {
  if (!target || typeof target !== "string") {
    throw new Error("target is required");
  }
  return shell.openExternal(target);
});

/* ===== Auth: login / logout ===== */
ipcMain.handle("auth.login", async (event, payload) => {
  return http
    .post("/auth/login", payload)
    .then((data) => {
      if (data && data.token) {
        setAuthToken(data.token);
        startSSE(event.sender).catch((err) => console.error("[Main] Auto start SSE failed:", err));
      }
      return data;
    })
    .catch((err) => {
      throw new Error(err.message);
    });
});

ipcMain.handle("auth.logout", async (_event, token) => {
  const t = token || getAuthToken();
  if (!t) return;

  const bearer = typeof t === "string" && t.startsWith("Bearer ") ? t : `Bearer ${t}`;

  try {
    // 断流接口也需要鉴权，必须在清 token 前完成。
    await stopSSE();
  } catch (err) {
    console.error("[Auth] Stop SSE before logout failed:", err);
  }

  return http
    .post("/auth/logout", null, {
      headers: { Authorization: bearer },
    })
    .then((data) => {
      setAuthToken(null);
      return data;
    })
    .catch((err) => {
      setAuthToken(null);
      throw new Error(err.message);
    });
});

ipcMain.handle("luna.app.quit", () => {
  app.quit();
});

ipcMain.handle("luna.window.setAlwaysOnTop", (event, flag) => {
  const win = BrowserWindow.fromWebContents(event.sender);
  if (win) {
    win.setAlwaysOnTop(flag);
  }
});

ipcMain.on("pet:mouse-enter", (event) => {
  try {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (win && !win.isDestroyed()) {
      win.setIgnoreMouseEvents(false);
    }
  } catch (err) {
    console.error("pet:mouse-enter error:", err);
  }
});

ipcMain.on("pet:mouse-leave", (event) => {
  try {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (win && !win.isDestroyed()) {
      win.setIgnoreMouseEvents(true, { forward: true });
    }
  } catch (err) {
    console.error("pet:mouse-leave error:", err);
  }
});

function createWindow() {
  const win = new BrowserWindow({
    width: 900,
    height: 700,
    frame: false,
    transparent: true,
    resizable: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    backgroundColor: "#00000000",
    hasShadow: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false,
    },
  });

  win.loadURL("http://localhost:5173");

  win.once("ready-to-show", () => {
    win.maximize();
    globalShortcut.register("CommandOrControl+L", () => {
      win.webContents.send("pet:toggle-chat");
      win.focus();
    });
  });

  // 默认穿透，只有鼠标进入组件时才取消穿透
  win.setIgnoreMouseEvents(true, { forward: true });

  win.on("minimize", (e) => e.preventDefault());
  win.on("hide", (e) => e.preventDefault());

  win.webContents.setBackgroundThrottling(false);
  win.webContents.openDevTools({ mode: "detach" });
}

app.whenReady().then(createWindow);

app.on("will-quit", () => {
  globalShortcut.unregisterAll();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
