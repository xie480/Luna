import { ipcMain } from "electron";
import http from "../httpClient.js";

export function registerMcpIpc() {
  console.log("[McpIPC] Registering IPC handlers...");

  // === Resources (獲取列表) ===
  ipcMain.handle("mcp.resource.list", async () => {
    // 根據文檔 2.7，獲取所有資源（包含 Tool 和 Skill）
    return http.get("/mcp/resources");
  });

  // === Tools (工具管理) ===
  
  // 2.1 註冊工具
  ipcMain.handle("mcp.tool.create", async (_, payload) => {
    return http.post("/mcp/tools", payload);
  });

  // 2.2 更新工具
  ipcMain.handle("mcp.tool.update", async (_, payload) => {
    return http.put("/mcp/tools", payload);
  });

  // 2.3 刪除工具
  ipcMain.handle("mcp.tool.delete", async (_, id) => {
    return http.delete(`/mcp/tools/${id}`);
  });

  // === Skills (技能管理 - 預留接口) ===
  ipcMain.handle("mcp.skill.create", async (_, payload) => {
    return http.post("/mcp/skills", payload);
  });

  ipcMain.handle("mcp.skill.update", async (_, payload) => {
    return http.put("/mcp/skills", payload);
  });

  ipcMain.handle("mcp.skill.delete", async (_, id) => {
    return http.delete(`/mcp/skills/${id}`);
  });

  // === Approval (敏感操作審批) ===
  ipcMain.handle("mcp.skill.approve", async (_, payload) => {
    return http.post("/mcp/skills/approval", payload);
  });
}
