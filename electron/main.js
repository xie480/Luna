// main.js
import { app, BrowserWindow, ipcMain, globalShortcut } from "electron";
import axios from "axios";
import path from "path";
import { fileURLToPath } from "url";

/* ===== 修复 __dirname ===== */
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// 【優化】禁用硬件加速
// 這行代碼解決了透明視窗在部分顯卡上導致的白屏/灰屏/閃爍問題
// 必須在 app.whenReady() 之前調用
app.disableHardwareAcceleration();

/* ===== HTTP 客户端 ===== */
let authToken = null;

const http = axios.create({
  baseURL: "http://localhost:8001",
  timeout: 1000000,
});

http.interceptors.request.use((config) => {
  if (authToken) {
    config.headers = config.headers || {};
    config.headers.Authorization = authToken;
  }
  return config;
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


/* ===== Auth: login / logout ===== */
ipcMain.handle("auth.login", async (_event, payload) => {
  const data = await http.post("/auth/login", payload).then(res => res.data);
  if (data && data.token) {
    authToken = data.token;
  }
  return data;
});

ipcMain.handle("auth.logout", async (_event, token) => {
  const t = token || authToken;
  if (!t) return;
  const res = await http.post("/auth/logout", null, {
    headers: { Authorization: t },
  }).then(res => res.data);
  authToken = null;
  return res;
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
      // 可选：如果需要输入框立即获得焦点，可以调用 win.focus()，
      // 但这可能会抢占其他应用焦点，视需求而定。
      // win.focus(); 
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
      // 确保窗口此时可以捕获鼠标（取消穿透），并获得焦点
      win.setIgnoreMouseEvents(false);
      win.focus();
    });
  });

  // 启动时允许穿透（forward: true 保证可在 renderer 转发事件）
  win.setIgnoreMouseEvents(true, { forward: true });

  win.on('minimize', (e) => e.preventDefault());
  win.on('hide', (e) => e.preventDefault());

  win.webContents.setBackgroundThrottling(false);

  // win.webContents.openDevTools({ mode: "detach" });
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
