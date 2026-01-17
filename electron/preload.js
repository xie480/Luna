const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("desktopApi", {
  chatMessage: (payload) =>
    ipcRenderer.invoke("luna.api.chat.message", payload),

  startup: () =>
    ipcRenderer.invoke("luna.api.chat.startup"),

  shutdown: () =>
    ipcRenderer.invoke("luna.api.chat.shutdown")
});
