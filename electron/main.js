// main.js
import { app, BrowserWindow, ipcMain, globalShortcut } from "electron";
import path from "path";
import { fileURLToPath } from "url";

// 引入統一的 HTTP 客戶端和 Token 管理
import http, { setAuthToken, getAuthToken } from "../src/main/httpClient.js";
// 引入我們寫好的 chatIpc
import { registerChatIpc } from "../src/main/ipc/chatIpc.js";

/* ===== 修复 __dirname ===== */
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// 註冊 chatIpc 中的監聽器 (Startup, Message, Shutdown, SSE)
registerChatIpc();

/* ===== IPC handlers for History API ===== */
ipcMain.handle("luna.api.chat.history.date", async (_event, yearMonth) => {
  console.log('[History] fetching available dates for', yearMonth);

  if (typeof yearMonth !== 'string') {
    throw new TypeError('yearMonth must be string');
  }

  // 由於 httpClient 已經配置了攔截器返回 res.data，這裡直接 return 即可
  return http.get('/luna/api/chat/history/date', {
    params: { ym: yearMonth }
  }).catch(err => { throw new Error(err.message); });
});

ipcMain.handle("luna.api.chat.history", async (_event, yearMonthDay) => {
  console.log('[History] fetching chat history for', yearMonthDay);
  if (typeof yearMonthDay !== 'string') {
    throw new TypeError('yearMonthDay must be string');
  }
  return http.get('/luna/api/chat/history', { params: { ymd: yearMonthDay } })
    .catch(err => { throw new Error(err.message); });
});

/* ===== Auth: login / logout ===== */
ipcMain.handle("auth.login", async (event, payload) => {
  return http.post("/auth/login", payload)
    .then(data => {
      if (data && data.token) {
        // 登錄成功後保存 Token，後續所有請求（包括 SSE）都會自動帶上
        setAuthToken(data.token);
      }
      return data;
    })
    .catch(err => { throw new Error(err.message); });
});

ipcMain.handle("auth.logout", async (_event, token) => {
  const t = token || getAuthToken();
  if (!t) return;
  return http.post("/auth/logout", null, {
    headers: { Authorization: t },
  })
  .then(data => {
    setAuthToken(null);
    return data;
  })
  .catch(err => { throw new Error(err.message); });
});

ipcMain.handle("luna.app.quit", () => {
  app.quit();
});

/* ===== pet enter/leave: 修复穿透问题 ===== */
ipcMain.on("pet:mouse-enter", (event) => {
  try {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (win && !win.isDestroyed()) {
      // 停止忽略鼠标事件，允许点击
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
      // 恢复穿透，forward: true 保证鼠标移动事件仍能传给前端
      win.setIgnoreMouseEvents(true, { forward: true });
    }
  } catch (err) {
    console.error("pet:mouse-leave error:", err);
  }
});

/* ===== Electron 启动 ===== */
function createWindow() {
  const win = new BrowserWindow({
    width: 900,
    height: 700,
    frame: false,        // 无系统边框
    transparent: true,   // 透明窗口
    resizable: true,
    alwaysOnTop: true,   // 桌宠关键
    skipTaskbar: true,   // 可选
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  win.loadURL("http://localhost:5173");

  // 最大化但保留任务栏
  win.once("ready-to-show", () => {
    win.maximize();

    // === 注册全局快捷键 Ctrl+L (Mac下为 Cmd+L) ===
    globalShortcut.register("CommandOrControl+L", () => {
      // 发送消息给渲染进程，切换输入框显示状态
      win.webContents.send("pet:toggle-chat");
      win.focus();
    });
  });

  // 启动时允许穿透（forward: true 保证可在 renderer 转发事件）
  win.setIgnoreMouseEvents(true, { forward: true });

  win.on('minimize', (e) => e.preventDefault());
  win.on('hide', (e) => e.preventDefault());

  win.webContents.setBackgroundThrottling(false);

  win.webContents.openDevTools({ mode: "detach" });
}

app.whenReady().then(createWindow);

app.on("will-quit", () => {
  // 注销所有快捷键
  globalShortcut.unregisterAll();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
