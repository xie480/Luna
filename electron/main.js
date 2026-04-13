import { app, BrowserWindow, globalShortcut, ipcMain, shell } from "electron";
import path from "path";
import { fileURLToPath } from "url";

import http, {
  getAuthToken,
  getErrorMessage,
  onAuthExpired,
  setAuthToken,
} from "../src/main/httpClient.js";
import { registerChatIpc, startSSE, stopSSE } from "../src/main/ipc/chatIpc.js";
import { registerMcpIpc } from "../src/main/ipc/mcpIpc.js";
import { registerPromptIpc } from "../src/main/ipc/promptIpc.js";
import { registerRagIpc } from "../src/main/ipc/ragIpc.js";

app.disableHardwareAcceleration();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

registerChatIpc();
registerMcpIpc();
registerPromptIpc();
registerRagIpc();

const removeAuthExpiredListener = onAuthExpired((payload = {}) => {
  stopSSE().catch((error) => {
    console.error("[Auth] Stop SSE after auth expired failed:", error);
  });

  BrowserWindow.getAllWindows().forEach((win) => {
    if (!win.isDestroyed()) {
      win.webContents.send("luna:auth-expired", payload);
    }
  });
});

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
      throw new Error(getErrorMessage(err));
    });
});

ipcMain.handle("luna.api.chat.history", async (_event, yearMonthDay) => {
  console.log("[History] fetching chat history for", yearMonthDay);

  if (typeof yearMonthDay !== "string") {
    throw new TypeError("yearMonthDay must be string");
  }

  return http
    .get("/luna/api/chat/history", {
      params: { ymd: yearMonthDay },
    })
    .catch((err) => {
      throw new Error(getErrorMessage(err));
    });
});

ipcMain.handle("luna.api.query.knowledge-base", async (_event, payload = {}) => {
  return http.post("/luna/api/query/knowledge-base", payload).catch((err) => {
    throw new Error(getErrorMessage(err));
  });
});

ipcMain.handle("luna.api.query.log", async (_event, payload = {}) => {
  return http.post("/luna/api/query/log", payload).catch((err) => {
    throw new Error(getErrorMessage(err));
  });
});

ipcMain.handle("luna.api.plan.run", async (_event, payload = {}) => {
  return http.post("/luna/api/plan/run", payload).catch((err) => {
    throw new Error(getErrorMessage(err));
  });
});

ipcMain.handle("luna.api.plan.phase.run", async (_event, payload = {}) => {
  return http.post("/luna/api/plan/phase/run", payload).catch((err) => {
    throw new Error(getErrorMessage(err));
  });
});

ipcMain.handle("luna.api.plan.report.finalize", async (_event, payload = {}) => {
  return http.post("/luna/api/plan/report/finalize", payload).catch((err) => {
    throw new Error(getErrorMessage(err));
  });
});

ipcMain.handle("luna.api.plan.graph", async (_event, planId) => {
  if (!planId || typeof planId !== "string") {
    throw new Error("planId is required");
  }

  return http
    .get(`/luna/api/plan/graph/${encodeURIComponent(planId)}`)
    .catch((err) => {
      throw new Error(getErrorMessage(err));
    });
});

ipcMain.handle("luna.app.openExternal", async (_event, target) => {
  if (!target || typeof target !== "string") {
    throw new Error("target is required");
  }

  return shell.openExternal(target);
});

ipcMain.handle("auth.login", async (event, payload) => {
  return http
    .post("/auth/login", payload)
    .then((data) => {
      if (data?.token) {
        setAuthToken(data.token);
        startSSE(event.sender).catch((err) => {
          console.error("[Main] Auto start SSE failed:", err);
        });
      }

      return data;
    })
    .catch((err) => {
      throw new Error(getErrorMessage(err));
    });
});

ipcMain.handle("auth.logout", async () => {
  const token = getAuthToken();
  if (!token) return null;

  const bearer = token.startsWith("Bearer ") ? token : `Bearer ${token}`;

  try {
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
      throw new Error(getErrorMessage(err));
    });
});

async function gracefullyCloseSessionForQuit() {
  const token = getAuthToken();

  if (token) {
    try {
      await http.post("/luna/api/chat/shutdown");
    } catch (err) {
      console.error("[App] Chat shutdown before quit failed:", getErrorMessage(err));
    }
  }

  try {
    await stopSSE();
  } catch (err) {
    console.error("[App] Stop SSE before quit failed:", err);
  }
}

ipcMain.handle("luna.app.quit", async () => {
  await gracefullyCloseSessionForQuit();
  app.quit();
  return true;
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

  win.setIgnoreMouseEvents(true, { forward: true });
  win.on("minimize", (event) => event.preventDefault());
  win.on("hide", (event) => event.preventDefault());

  win.webContents.setBackgroundThrottling(false);
  win.webContents.openDevTools({ mode: "detach" });
}

app.whenReady().then(createWindow);

app.on("will-quit", () => {
  globalShortcut.unregisterAll();
  removeAuthExpiredListener?.();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
