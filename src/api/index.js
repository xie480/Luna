export const startup = async () => {
  return window.desktopApi.startup();
};

export const chat = async (payload) => {
  return window.desktopApi.chat(payload);
};

export const shutdown = async () => {
  return window.desktopApi.shutdown();
};
