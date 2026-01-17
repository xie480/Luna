import { ipcMain } from "electron";
import http from "../httpClient.js";

export function registerChatIpc() {

  // === 启动 ===
  ipcMain.handle("luna.api.chat.startup", async () => {
    return http.post("/luna/api/chat/startup");
  });

  // === 聊天 ===
  ipcMain.handle("luna.api.chat.message", async (_, payload) => {
    return http.post("/luna/api/chat/message", payload);
  });

  // === 关闭 ===
  ipcMain.handle("luna.api.chat.shutdown", async () => {
    return http.post("/luna/api/chat/shutdown");
  });
}
