const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("desktopApi", {
  chatMessage: (payload) => ipcRenderer.invoke("luna.api.chat.message", payload),
  startup:     () => ipcRenderer.invoke("luna.api.chat.startup"),
  shutdown:    () => ipcRenderer.invoke("luna.api.chat.shutdown"),
  historyDate: (date) => ipcRenderer.invoke("luna.api.chat.history.date", date),
  history:     (date) => ipcRenderer.invoke("luna.api.chat.history", date),
  login:       (payload) => ipcRenderer.invoke("auth.login", payload),
  logout:      (token)   => ipcRenderer.invoke("auth.logout", token),
  quit:        () => ipcRenderer.invoke("luna.app.quit"),
  setAlwaysOnTop: (flag) => ipcRenderer.invoke("luna.window.setAlwaysOnTop", flag),

  // 查询接口
  queryKnowledgeBase: (payload) => ipcRenderer.invoke("luna.api.query.knowledge-base", payload),
  queryLog: (payload) => ipcRenderer.invoke("luna.api.query.log", payload),
  ragRetrieve: (payload) => ipcRenderer.invoke("luna.api.rag.retrieve", payload),

  // OpenClaw Plan 接口
  planRun: (payload) => ipcRenderer.invoke("luna.api.plan.run", payload),
  planPhaseRun: (payload) => ipcRenderer.invoke("luna.api.plan.phase.run", payload),
  planFinalizeReport: (payload) => ipcRenderer.invoke("luna.api.plan.report.finalize", payload),
  planGraph: (planId) => ipcRenderer.invoke("luna.api.plan.graph", planId),
  openExternal: (target) => ipcRenderer.invoke("luna.app.openExternal", target),

  // 監聽狀態更新
  onStatusUpdate: (callback) => ipcRenderer.on('luna:status-update', (_event, value) => callback(value)),
});

contextBridge.exposeInMainWorld("mcpApi", {
  // 資源
  listResources: () => ipcRenderer.invoke("mcp.resource.list"),
  getResourceById: (id) => ipcRenderer.invoke("mcp.resource.get", id),
  searchResources: (payload) => ipcRenderer.invoke("mcp.resource.search", payload),

  // Tool 管理
  createTool: (payload) => ipcRenderer.invoke("mcp.tool.create", payload),
  updateTool: (payload) => ipcRenderer.invoke("mcp.tool.update", payload),
  deleteTool: (id)      => ipcRenderer.invoke("mcp.tool.delete", id),
  listTools: (payload) => ipcRenderer.invoke("mcp.tool.list", payload),
  callTool: (payload) => ipcRenderer.invoke("mcp.tool.call", payload),
  
  // Skill 管理
  listSkills: () => ipcRenderer.invoke("mcp.skill.list"),
  getSkillDetail: (id) => ipcRenderer.invoke("mcp.skill.detail", id),
  createSkill: (payload) => ipcRenderer.invoke("mcp.skill.create", payload),
  updateSkill: (payload) => ipcRenderer.invoke("mcp.skill.update", payload),
  deleteSkill: (id)      => ipcRenderer.invoke("mcp.skill.delete", id),

  // 審批管理
  approveTool: (payload) => ipcRenderer.invoke("mcp.tool.approve", payload),
  approveSkill: (payload) => ipcRenderer.invoke("mcp.skill.approve", payload),

  // Prompt / Resource 目录与调试
  listPrompts: (payload) => ipcRenderer.invoke("mcp.prompt.list", payload),
  getPrompt: (payload) => ipcRenderer.invoke("mcp.prompt.get", payload),
  listCatalogResources: (payload) => ipcRenderer.invoke("mcp.catalog.resource.list", payload),
  readCatalogResource: (payload) => ipcRenderer.invoke("mcp.catalog.resource.read", payload),

  // 管理写入
  syncCatalog: () => ipcRenderer.invoke("mcp.catalog.sync"),
  saveServerRegistry: (payload) => ipcRenderer.invoke("mcp.migrate.serverRegistry", payload),
  saveToolCatalog: (payload) => ipcRenderer.invoke("mcp.migrate.toolCatalog", payload),
  saveToolImplMapping: (payload) => ipcRenderer.invoke("mcp.migrate.toolImplMapping", payload),
  savePromptCatalog: (payload) => ipcRenderer.invoke("mcp.migrate.promptCatalog", payload),
  saveResourceCatalog: (payload) => ipcRenderer.invoke("mcp.migrate.resourceCatalog", payload),
  saveWorkflowTemplate: (payload) => ipcRenderer.invoke("mcp.migrate.workflowTemplate", payload),
  callRpc: (payload) => ipcRenderer.invoke("mcp.rpc.call", payload),
});

contextBridge.exposeInMainWorld("promptApi", {
  listCategories: () => ipcRenderer.invoke("prompt.category.list"),
  listCategoryDetails: () => ipcRenderer.invoke("prompt.category.detail.list"),
  getCategoryTree: () => ipcRenderer.invoke("prompt.category.tree"),
  listItemsByCategory: (payload) => ipcRenderer.invoke("prompt.item.listByCategory", payload),
  getItemDetail: (payload) => ipcRenderer.invoke("prompt.item.detail", payload),
  getItemDetailById: (payload) => ipcRenderer.invoke("prompt.item.detailById", payload),
  checkItemExists: (payload) => ipcRenderer.invoke("prompt.item.exists", payload),
  getItemKeyValues: (payload) => ipcRenderer.invoke("prompt.item.keyValues", payload),
  searchItems: (payload) => ipcRenderer.invoke("prompt.search", payload),
  createItem: (payload) => ipcRenderer.invoke("prompt.item.create", payload),
  updateItem: (payload) => ipcRenderer.invoke("prompt.item.update", payload),
  saveItem: (payload) => ipcRenderer.invoke("prompt.item.save", payload),
  deleteItem: (payload) => ipcRenderer.invoke("prompt.item.delete", payload),
  listVersions: (payload) => ipcRenderer.invoke("prompt.version.list", payload),
  getVersionDetail: (payload) => ipcRenderer.invoke("prompt.version.detail", payload),
  activateVersion: (payload) => ipcRenderer.invoke("prompt.version.activate", payload),
  rollbackVersion: (payload) => ipcRenderer.invoke("prompt.version.rollback", payload),
  saveDraft: (payload) => ipcRenderer.invoke("prompt.version.draft", payload),
  archiveVersion: (payload) => ipcRenderer.invoke("prompt.version.archive", payload),
  diffVersions: (payload) => ipcRenderer.invoke("prompt.version.diff", payload),
  previewMatch: (payload) => ipcRenderer.invoke("prompt.preview.match", payload),
  previewAssemble: (payload) => ipcRenderer.invoke("prompt.preview.assemble", payload),
  listPolicies: () => ipcRenderer.invoke("prompt.policy.list"),
  getPolicyDetail: (payload) => ipcRenderer.invoke("prompt.policy.detail", payload),
  savePolicy: (payload) => ipcRenderer.invoke("prompt.policy.save", payload),
  deletePolicy: (payload) => ipcRenderer.invoke("prompt.policy.delete", payload),
  listPolicyVersions: (payload) => ipcRenderer.invoke("prompt.policy.version.list", payload),
  activatePolicyVersion: (payload) => ipcRenderer.invoke("prompt.policy.version.activate", payload),
});

contextBridge.exposeInMainWorld("pet", {
  enter: () => ipcRenderer.send("pet:mouse-enter"),
  leave: () => ipcRenderer.send("pet:mouse-leave"),
  // 监听快捷键切换聊天框
  onToggleChat: (callback) => ipcRenderer.on("pet:toggle-chat", (_event, value) => callback(value)),
});
