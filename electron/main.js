import { app, BrowserWindow, ipcMain } from "electron";
import axios from "axios";
import path from "path";
import { fileURLToPath } from "url";

/* ===== 修复 __dirname ===== */
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/* ===== HTTP 客户端 ===== */
const http = axios.create({
  baseURL: "http://localhost:8080",
  timeout: 10000
});

/* ===== IPC ===== */
ipcMain.handle("luna.api.chat.startup", async () => {
  return http.post("/luna/api/chat/startup").then(res => res.data);
});

ipcMain.handle("luna.api.chat.message", async (_, payload) => {
  return http.post("/luna/api/chat/message", payload).then(res => res.data);
});

ipcMain.handle("luna.api.chat.shutdown", async () => {
  return http.post("/luna/api/chat/shutdown").then(res => res.data);
});

/* ===== Electron 启动 ===== */
app.whenReady().then(() => {
  const win = new BrowserWindow({
    webPreferences: {
      preload: path.join(__dirname, "preload.js")
    }
  });

  win.loadURL("http://localhost:5173");
});