import { ipcMain } from "electron";
import http, { getErrorMessage } from "../httpClient.js";

// 全局變量管理 SSE 連接狀態
let currentStream = null;
let intentionalShutdown = false;
let reconnectTimer = null;

// 封裝 SSE 連接邏輯
async function connectSSE(sender, isFirstAttempt = false) {
  if (intentionalShutdown) {
    console.log("[ChatIPC] Intentional shutdown active, skipping connect.");
    return;
  }

  // 如果已經有流在運行，先清理
  if (currentStream) {
    console.log("[ChatIPC] Cleaning up existing stream before reconnecting.");
    try { currentStream.destroy(); } catch (e) { console.error("[ChatIPC] Error destroying stream:", e); }
    currentStream = null;
  }

  console.log(`[ChatIPC] ${isFirstAttempt ? "Starting" : "Reconnecting"} stream connection to /api/luna/status/stream...`);

  try {
    const responseOrStream = await http.get("/api/luna/status/stream", {
      headers: {
        Accept: "text/event-stream",
        "Cache-Control": "no-cache",
        Connection: "keep-alive",
        "X-Accel-Buffering": "no",
      },
      responseType: "stream",
      adapter: "http",
      timeout: 0,
    });

    let stream;
    if (responseOrStream && responseOrStream.data && typeof responseOrStream.data.on === "function") {
      console.log("[ChatIPC] Received full response object.");
      stream = responseOrStream.data;
    } else if (responseOrStream && typeof responseOrStream.on === "function") {
      console.log("[ChatIPC] Received stream directly (Interceptor active).");
      stream = responseOrStream;
    } else {
      console.error("[ChatIPC] Invalid response type:", typeof responseOrStream, responseOrStream);
      throw new Error("Response is not a stream");
    }

    currentStream = stream;
    let buffer = "";

    console.log("[ChatIPC] Stream listener attaching...");

    stream.on("data", (chunk) => {
      const chunkStr = chunk.toString();
      buffer += chunkStr;

      const parts = buffer.split("\n\n");
      buffer = parts.pop();

      parts.forEach((part) => {
        if (!part.trim()) return;

        const lines = part.split("\n");
        let eventName = null;
        let dataStr = "";

        lines.forEach((line) => {
          if (line.startsWith("event:")) {
            eventName = line.substring(6).trim();
          } else if (line.startsWith("data:")) {
            dataStr += line.replace(/^data:\s?/, "");
          }
        });

        if (!dataStr) return;

        console.log(`[ChatIPC] Parsed Event: ${eventName || "message"}, Data Length: ${dataStr.length}`);

        let parsedData = dataStr;
        try {
          parsedData = JSON.parse(dataStr);
        } catch {}

        // 统一把 event + data 发给前端，前端可准确分流 APPROVAL_REQUEST / luna-status
        sender.send("luna:status-update", {
          event: eventName || "message",
          data: parsedData,
        });
      });
    });

    stream.on("error", (err) => {
      console.error("[ChatIPC] Stream Error:", err);
      currentStream = null;
      scheduleReconnect(sender);
    });

    stream.on("end", () => {
      console.log("[ChatIPC] Stream ended by server.");
      currentStream = null;
      scheduleReconnect(sender);
    });

    console.log("[ChatIPC] Stream setup complete.");
  } catch (error) {
    console.error("[ChatIPC] Connection failed:", error);
    if (isFirstAttempt) {
      throw error;
    } else {
      scheduleReconnect(sender);
    }
  }
}

// 調度重連
function scheduleReconnect(sender) {
  if (intentionalShutdown) return;
  if (reconnectTimer) return;

  console.log("[ChatIPC] Scheduling reconnect in 3s...");
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectSSE(sender);
  }, 3000);
}

// 導出啟動方法供 main.js 在登錄後調用
export async function startSSE(sender, isFirstAttempt = true) {
  console.log("[ChatIPC] startSSE called.");
  intentionalShutdown = false;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  await connectSSE(sender, isFirstAttempt);
}

// 導出關閉方法供 main.js 在登出時調用
export async function stopSSE() {
  console.log("[ChatIPC] stopSSE called.");
  intentionalShutdown = true;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (currentStream) {
    console.log("[ChatIPC] Destroying active stream.");
    currentStream.destroy();
    currentStream = null;
  }
  try {
    await http.get("/api/luna/status/disconnect");
  } catch (e) {
    console.error("[ChatIPC] Disconnect error:", getErrorMessage(e));
  }
}

export function registerChatIpc() {
  console.log("[ChatIPC] Registering IPC handlers...");

  ipcMain.handle("luna.api.chat.startup", async (event) => {
    console.log("[ChatIPC] IPC 'luna.api.chat.startup' invoked.");

    await startSSE(event.sender, false);

    try {
      console.log("[ChatIPC] Calling backend chat startup endpoint...");
      const response = await http.post("/luna/api/chat/startup");
      console.log("[ChatIPC] Chat startup response:", response);
      return response;
    } catch (error) {
      console.error("[ChatIPC] Chat startup failed:", error);
      if (error?.response?.status === 503 && error?.response?.data) {
        return error.response.data;
      }
      throw new Error(getErrorMessage(error));
    }
  });

  ipcMain.handle("luna.api.chat.message", async (_, payload) => {
    console.log("[ChatIPC] IPC 'luna.api.chat.message' invoked.");
    try {
      return await http.post("/luna/api/chat/message", payload);
    } catch (error) {
      if ((error?.response?.status === 400 || error?.response?.status === 503) && error?.response?.data) {
        return error.response.data;
      }
      throw new Error(getErrorMessage(error));
    }
  });

  ipcMain.handle("luna.api.chat.shutdown", async () => {
    console.log("[ChatIPC] IPC 'luna.api.chat.shutdown' invoked.");

    try {
      console.log("[ChatIPC] Calling backend chat shutdown endpoint...");
      await http.post("/luna/api/chat/shutdown");
      return null;
    } catch (error) {
      console.error("[ChatIPC] Chat shutdown failed:", error);
      return null;
    }
  });
}
