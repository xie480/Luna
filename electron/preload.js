const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("desktopApi", {
  chatMessage: (payload) =>
    ipcRenderer.invoke("luna.api.chat.message", payload),

  startup: () =>
    ipcRenderer.invoke("luna.api.chat.startup"),

  shutdown: () =>
    ipcRenderer.invoke("luna.api.chat.shutdown"),

  historyDate: (date) =>
    ipcRenderer.invoke("luna.api.chat.history.date", date),
  
  history: (date) =>
    ipcRenderer.invoke("luna.api.chat.history", date),
});

contextBridge.exposeInMainWorld("pet", {
  enter: () => ipcRenderer.send("pet:mouse-enter"),
  leave: () => ipcRenderer.send("pet:mouse-leave"),
});