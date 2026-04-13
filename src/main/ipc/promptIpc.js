import { ipcMain } from "electron";
import http, { getErrorMessage } from "../httpClient.js";

function withQuery(url, params = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, value);
    }
  });
  const query = search.toString();
  return query ? `${url}?${query}` : url;
}

function registerGet(channel, url, mapPayload) {
  ipcMain.handle(channel, async (_, payload = {}) => {
    try {
      return await http.get(withQuery(url, mapPayload ? mapPayload(payload) : payload));
    } catch (error) {
      throw new Error(getErrorMessage(error));
    }
  });
}

function registerPost(channel, url) {
  ipcMain.handle(channel, async (_, payload = {}) => {
    try {
      return await http.post(url, payload);
    } catch (error) {
      throw new Error(getErrorMessage(error));
    }
  });
}

export function registerPromptIpc() {
  console.log("[PromptIPC] Registering IPC handlers...");

  registerGet("prompt.category.list", "/api/prompt/categories");
  registerGet("prompt.category.detail.list", "/api/prompt/categories/detail");
  registerGet("prompt.category.tree", "/api/prompt/categories/tree");

  registerGet("prompt.item.listByCategory", "/api/prompt/items", (payload = {}) => ({
    category: payload.category,
    subCategory: payload.subCategory,
  }));
  registerGet("prompt.item.detail", "/api/prompt/item/detail", (payload = {}) => ({
    key: payload.key,
  }));
  registerGet("prompt.item.detailById", "/api/prompt/item/detail-by-id", (payload = {}) => ({
    id: payload.id,
  }));
  registerGet("prompt.item.exists", "/api/prompt/item/exists", (payload = {}) => ({
    key: payload.key,
  }));
  registerGet("prompt.item.keyValues", "/api/prompt/item/key-values", (payload = {}) => ({
    category: payload.category,
  }));

  registerPost("prompt.search", "/api/prompt/search");
  registerPost("prompt.item.create", "/api/prompt/item/create");
  registerPost("prompt.item.update", "/api/prompt/item/update");
  registerPost("prompt.item.save", "/api/prompt/item/save");
  registerPost("prompt.item.delete", "/api/prompt/item/delete");

  registerGet("prompt.version.list", "/api/prompt/item/versions", (payload = {}) => ({
    key: payload.key,
  }));
  registerGet("prompt.version.detail", "/api/prompt/item/version/detail", (payload = {}) => ({
    versionId: payload.versionId,
  }));
  registerPost("prompt.version.activate", "/api/prompt/item/activate");
  registerPost("prompt.version.rollback", "/api/prompt/item/rollback");
  registerPost("prompt.version.draft", "/api/prompt/item/draft");
  registerPost("prompt.version.archive", "/api/prompt/item/archive");
  registerPost("prompt.version.diff", "/api/prompt/item/diff");

  registerPost("prompt.preview.match", "/api/prompt/preview/match");
  registerPost("prompt.preview.assemble", "/api/prompt/preview/assemble");

  registerGet("prompt.policy.list", "/api/prompt/policy/list");
  registerGet("prompt.policy.detail", "/api/prompt/policy/detail", (payload = {}) => ({
    policyId: payload.policyId,
  }));
  registerPost("prompt.policy.save", "/api/prompt/policy/save");
  registerPost("prompt.policy.delete", "/api/prompt/policy/delete");
  registerGet("prompt.policy.version.list", "/api/prompt/policy/versions", (payload = {}) => ({
    policyId: payload.policyId,
  }));
  registerPost("prompt.policy.version.activate", "/api/prompt/policy/activate");
}
