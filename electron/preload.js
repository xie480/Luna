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
  
  // 監聽狀態更新
  onStatusUpdate: (callback) => ipcRenderer.on('luna:status-update', (_event, value) => callback(value)),
});

contextBridge.exposeInMainWorld("pet", {
  enter: () => ipcRenderer.send("pet:mouse-enter"),
  leave: () => ipcRenderer.send("pet:mouse-leave"),
  // 监听快捷键切换聊天框
  onToggleChat: (callback) => ipcRenderer.on("pet:toggle-chat", (_event, value) => callback(value)),
});
