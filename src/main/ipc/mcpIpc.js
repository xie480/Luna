import { ipcMain } from "electron";
import http from "../httpClient.js";

export function registerMcpIpc() {
  console.log("[McpIPC] Registering IPC handlers...");

  // === Resources (资源查询) ===
  // 获取所有资源（Tool + Skill）
  ipcMain.handle("mcp.resource.list", async () => {
    return http.get("/mcp/resources");
  });

  // 获取单个资源详情（Tool 或 Skill）
  ipcMain.handle("mcp.resource.get", async (_, id) => {
    return http.get(`/mcp/resources/${id}`);
  });

  // === Tools (工具管理) ===
  ipcMain.handle("mcp.tool.create", async (_, payload) => {
    return http.post("/mcp/tools", payload);
  });

  ipcMain.handle("mcp.tool.update", async (_, payload) => {
    return http.put("/mcp/tools", payload);
  });

  ipcMain.handle("mcp.tool.delete", async (_, id) => {
    return http.delete(`/mcp/tools/${id}`);
  });

  // === Skills (技能管理) ===
  // 按文档：列表通过 /mcp/resources 过滤 type=SKILL
  ipcMain.handle("mcp.skill.list", async () => {
    const resources = await http.get("/mcp/resources");
    if (!Array.isArray(resources)) return [];
    return resources.filter((r) => r?.type === "SKILL");
  });

  // 按文档：详情通过 /mcp/resources/{id} 获取后校验 type=SKILL
  ipcMain.handle("mcp.skill.detail", async (_, id) => {
    const resource = await http.get(`/mcp/resources/${id}`);
    if (resource && resource.type && resource.type !== "SKILL") {
      throw new Error(`Resource ${id} is not a SKILL`);
    }
    return resource;
  });

  ipcMain.handle("mcp.skill.create", async (_, payload) => {
    return http.post("/mcp/skills", payload);
  });

  ipcMain.handle("mcp.skill.update", async (_, payload) => {
    return http.put("/mcp/skills", payload);
  });

  ipcMain.handle("mcp.skill.delete", async (_, id) => {
    return http.delete(`/mcp/skills/${id}`);
  });

  // === Approval (敏感操作审批) ===
  ipcMain.handle("mcp.skill.approve", async (_, payload) => {
    return http.post("/mcp/skills/approval", payload);
  });
}
