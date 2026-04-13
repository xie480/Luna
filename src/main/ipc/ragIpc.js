import { ipcMain } from "electron";
import http, { getErrorMessage } from "../httpClient.js";

export function registerRagIpc() {
  console.log("[RagIPC] Registering IPC handlers...");

  ipcMain.handle("luna.api.rag.retrieve", async (_, payload = {}) => {
    try {
      return await http.post("/luna/api/rag/retrieve", payload);
    } catch (error) {
      if (error?.response?.status === 422) {
        return error.response?.data ?? {};
      }
      throw new Error(getErrorMessage(error, "RAG retrieve failed"));
    }
  });
}
