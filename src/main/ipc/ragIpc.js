import { ipcMain } from "electron";
import http from "../httpClient.js";

export function registerRagIpc() {
  console.log("[RagIPC] Registering IPC handlers...");

  ipcMain.handle("luna.api.rag.retrieve", async (_, payload = {}) => {
    try {
      return await http.post("/luna/api/rag/retrieve", payload);
    } catch (error) {
      if (error?.response?.status === 422) {
        return error.response?.data ?? {};
      }
      throw new Error(error?.message || "RAG retrieve failed");
    }
  });
}
