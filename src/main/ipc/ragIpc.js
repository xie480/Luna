import { ipcMain } from "electron";
import http from "../httpClient.js";

export function registerRagIpc() {
  console.log("[RagIPC] Registering IPC handlers...");

  ipcMain.handle("luna.api.rag.retrieve", async (_, payload = {}) => {
    return http.post("/luna/api/rag/retrieve", payload);
  });
}
