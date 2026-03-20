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
  
  // 監聽狀態更新
  onStatusUpdate: (callback) => ipcRenderer.on('luna:status-update', (_event, value) => callback(value)),
});

contextBridge.exposeInMainWorld("mcpApi", {
  // 資源列表
  listResources: () => ipcRenderer.invoke("mcp.resource.list"),
  
  // Tool 管理
  createTool: (payload) => ipcRenderer.invoke("mcp.tool.create", payload),
  updateTool: (payload) => ipcRenderer.invoke("mcp.tool.update", payload),
  deleteTool: (id)      => ipcRenderer.invoke("mcp.tool.delete", id),
  
  // Skill 管理 (預留)
  createSkill: (payload) => ipcRenderer.invoke("mcp.skill.create", payload),
  updateSkill: (payload) => ipcRenderer.invoke("mcp.skill.update", payload),
  deleteSkill: (id)      => ipcRenderer.invoke("mcp.skill.delete", id),

  // 審批管理
  approveSkill: (payload) => ipcRenderer.invoke("mcp.skill.approve", payload),
});

contextBridge.exposeInMainWorld("pet", {
  enter: () => ipcRenderer.send("pet:mouse-enter"),
  leave: () => ipcRenderer.send("pet:mouse-leave"),
  // 监听快捷键切换聊天框
  onToggleChat: (callback) => ipcRenderer.on("pet:toggle-chat", (_event, value) => callback(value)),
});
