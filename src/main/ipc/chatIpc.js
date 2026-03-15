import { ipcMain } from "electron";
import http from "../httpClient.js";

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
    try { currentStream.destroy(); } catch(e) { console.error("[ChatIPC] Error destroying stream:", e); }
    currentStream = null;
  }

  console.log(`[ChatIPC] ${isFirstAttempt ? 'Starting' : 'Reconnecting'} stream connection to /api/luna/status/stream...`);

  try {
    // 根據新接口：GET /api/luna/status/stream
    const responseOrStream = await http.get("/api/luna/status/stream", {
      headers: {
        'Accept': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
        'X-Accel-Buffering': 'no', // 防止 Nginx 緩衝 SSE 流
      },
      responseType: 'stream',
      adapter: 'http', // 強制使用 Node.js HTTP 適配器
      timeout: 0 // ⚠️ 關鍵修復：SSE 是長連接，必須禁用超時，否則會被全局的 10s timeout 切斷
    });

    // 兼容性處理：判斷返回的是完整的 response 對象還是已經被攔截器解包的 stream
    let stream;
    if (responseOrStream && responseOrStream.data && typeof responseOrStream.data.on === 'function') {
      console.log("[ChatIPC] Received full response object.");
      stream = responseOrStream.data;
    } else if (responseOrStream && typeof responseOrStream.on === 'function') {
      console.log("[ChatIPC] Received stream directly (Interceptor active).");
      stream = responseOrStream;
    } else {
      console.error("[ChatIPC] Invalid response type:", typeof responseOrStream, responseOrStream);
      throw new Error("Response is not a stream");
    }

    currentStream = stream;
    let buffer = "";

    console.log("[ChatIPC] Stream listener attaching...");

    // 監聽數據流
    stream.on('data', (chunk) => {
      const chunkStr = chunk.toString();
      console.log(`[ChatIPC] DATA CHUNK:`, JSON.stringify(chunkStr));
      
      buffer += chunkStr;
      
      const parts = buffer.split('\n\n');
      buffer = parts.pop();

      parts.forEach(part => {
        if (!part.trim()) return;

        const lines = part.split('\n');
        let eventName = null;
        let dataStr = "";

        lines.forEach(line => {
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            dataStr += line.substring(5).trim();
          }
        });

        if (dataStr) {
          console.log(`[ChatIPC] Parsed Event: ${eventName || 'default'}, Data: ${dataStr}`);
          try {
            const data = JSON.parse(dataStr);
            sender.send('luna:status-update', data);
          } catch (e) {
            sender.send('luna:status-update', dataStr);
          }
        }
      });
    });

    stream.on('error', (err) => {
      console.error("[ChatIPC] Stream Error:", err);
      currentStream = null;
      scheduleReconnect(sender);
    });

    stream.on('end', () => {
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
    console.error("[ChatIPC] Disconnect error:", e.message);
  }
}

export function registerChatIpc() {
  console.log("[ChatIPC] Registering IPC handlers...");

  // === 启动 ===
  ipcMain.handle("luna.api.chat.startup", async (event) => {
    console.log("[ChatIPC] IPC 'luna.api.chat.startup' invoked.");
    await startSSE(event.sender, true);
    return { status: "connected" };
  });

  // === 聊天 ===
  ipcMain.handle("luna.api.chat.message", async (_, payload) => {
    console.log("[ChatIPC] IPC 'luna.api.chat.message' invoked.");
    return http.post("/luna/api/chat/message", payload);
  });

  // === 关闭 ===
  ipcMain.handle("luna.api.chat.shutdown", async () => {
    console.log("[ChatIPC] IPC 'luna.api.chat.shutdown' invoked.");
    await stopSSE();
    return { status: "disconnected" };
  });
}
