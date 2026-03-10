export const startup = async () => {
  return window.desktopApi.startup();
};

export const chat = async (payload) => {
  return window.desktopApi.chatMessage(payload);
};

export const shutdown = async () => {
  return window.desktopApi.shutdown();
};

export const historyDate = async (payload) => {
  return window.desktopApi.historyDate(payload);
};

export const history = async (payload) => {
  return window.desktopApi.history(payload);
};

export const login = async (payload) => {
  return window.desktopApi.login(payload);
};

export const logout = async (token) => {
  return window.desktopApi.logout(token);
};