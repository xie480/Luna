import { ipcMain } from "electron";
import http, { getErrorMessage } from "../httpClient.js";

function registerGet(channel, urlBuilder) {
  ipcMain.handle(channel, async (_, payload) => {
    try {
      return await http.get(urlBuilder(payload));
    } catch (error) {
      throw new Error(getErrorMessage(error));
    }
  });
}

function registerPost(channel, url, mapPayload) {
  ipcMain.handle(channel, async (_, payload) => {
    try {
      return await http.post(url, mapPayload ? mapPayload(payload) : payload);
    } catch (error) {
      throw new Error(getErrorMessage(error));
    }
  });
}

export function registerMcpIpc() {
  console.log("[McpIPC] Registering IPC handlers...");

  registerGet("mcp.resource.list", () => "/mcp/resources");
  registerGet("mcp.resource.get", (id) => `/mcp/resources/${encodeURIComponent(id)}`);
  registerPost("mcp.resource.search", "/mcp/search", (payload = {}) => ({
    query: payload.query ?? "",
  }));

  registerGet("mcp.tool.list", (payload = {}) => {
    const search = new URLSearchParams();
    if (payload?.serverCode) search.set("serverCode", payload.serverCode);
    const query = search.toString();
    return query ? `/mcp/tools/list?${query}` : "/mcp/tools/list";
  });
  registerPost("mcp.tool.call", "/mcp/tools/call");

  registerGet("mcp.prompt.list", (payload = {}) => {
    const search = new URLSearchParams();
    if (payload?.serverCode) search.set("serverCode", payload.serverCode);
    const query = search.toString();
    return query ? `/mcp/prompts/list?${query}` : "/mcp/prompts/list";
  });
  registerPost("mcp.prompt.get", "/mcp/prompts/get");

  registerGet("mcp.catalog.resource.list", (payload = {}) => {
    const search = new URLSearchParams();
    if (payload?.serverCode) search.set("serverCode", payload.serverCode);
    const query = search.toString();
    return query ? `/mcp/resources/list?${query}` : "/mcp/resources/list";
  });
  registerPost("mcp.catalog.resource.read", "/mcp/resources/read");

  registerPost("mcp.catalog.sync", "/mcp/catalog/sync", () => undefined);
  registerPost("mcp.migrate.serverRegistry", "/mcp/migrate/server-registry");
  registerPost("mcp.migrate.toolCatalog", "/mcp/migrate/tool-catalog");
  registerPost("mcp.migrate.toolImplMapping", "/mcp/migrate/tool-impl-mapping");
  registerPost("mcp.migrate.promptCatalog", "/mcp/migrate/prompt-catalog");
  registerPost("mcp.migrate.resourceCatalog", "/mcp/migrate/resource-catalog");
  registerPost("mcp.migrate.workflowTemplate", "/mcp/migrate/workflow-template");

  registerPost("mcp.rpc.call", "/mcp/rpc");

  ipcMain.handle("mcp.skill.list", async () => {
    try {
      const resources = await http.get("/mcp/resources");
      if (!Array.isArray(resources)) return [];
      return resources.filter((item) => item?.type === "WORKFLOW" || item?.type === "SKILL");
    } catch (error) {
      throw new Error(getErrorMessage(error));
    }
  });

  ipcMain.handle("mcp.skill.detail", async (_, id) => {
    try {
      const resource = await http.get(`/mcp/resources/${id}`);
      if (resource && resource.type && !["WORKFLOW", "SKILL"].includes(resource.type)) {
        throw new Error(`Resource ${id} is not a workflow/skill`);
      }
      return resource;
    } catch (error) {
      throw new Error(getErrorMessage(error));
    }
  });

  registerPost("mcp.tool.approve", "/mcp/tools/approval");
}
