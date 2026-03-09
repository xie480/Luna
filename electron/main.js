// main.js
import { app, BrowserWindow, ipcMain } from "electron";
import axios from "axios";
import path from "path";
import { fileURLToPath } from "url";

/* ===== 修复 __dirname ===== */
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/* ===== HTTP 客户端 ===== */
const http = axios.create({
  baseURL: "http://localhost:8001",
  timeout: 1000000
});

/* ===== IPC handlers for your chat API (unchanged) ===== */
ipcMain.handle("luna.api.chat.startup", async () => {
  return http.post("/luna/api/chat/startup").then(res => res.data);
});

ipcMain.handle("luna.api.chat.message", async (_, payload) => {
  return http.post("/luna/api/chat/message", payload).then(res => res.data);
});

ipcMain.handle("luna.api.chat.shutdown", async () => {
  return http.post("/luna/api/chat/shutdown").then(res => res.data);
});

ipcMain.handle("luna.api.chat.history.date",async (_event, yearMonth) => {
    console.log('[History] fetching available dates for', yearMonth);

    if (typeof yearMonth !== 'string') {
      throw new TypeError('yearMonth must be string');
    }

    return http.get('/luna/api/chat/history/date', {
      params: { ym: yearMonth }
    }).then(res => res.data);
  }
);


ipcMain.handle("luna.api.chat.history", async (_event, yearMonthDay) => {
  console.log('[History] fetching chat history for', yearMonthDay);
  if (typeof yearMonthDay !== 'string') {
    throw new TypeError('yearMonthDay must be string');
  }
  return http.get('/luna/api/chat/history', { params: { ymd: yearMonthDay } }).then(res => res.data);
});

/* ===== pet enter/leave: use event.sender to get the right BrowserWindow ===== */
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

/* ===== Electron 启动 ===== */
function createWindow() {
  const win = new BrowserWindow({
    width: 900,
    height: 700,
    frame: false,        // 无系统边框
    transparent: true,   // 透明窗口（可选）
    resizable: true,
    alwaysOnTop: true,   // 桌宠关键
    skipTaskbar: true, // 可选
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
  });

  // 启动时允许穿透（forward: true 保证可在 renderer 转发事件）
  win.setIgnoreMouseEvents(true, { forward: true });

  win.on('minimize', (e) => e.preventDefault());
  win.on('hide', (e) => e.preventDefault());

  win.webContents.setBackgroundThrottling(false);

  win.webContents.openDevTools({ mode: "detach" });

}

app.whenReady().then(createWindow);

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
