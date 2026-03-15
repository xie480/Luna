import { ipcMain } from "electron";
import http from "../httpClient.js";

// 全局變量管理 SSE 連接狀態
let currentStream = null;
let intentionalShutdown = false;
let reconnectTimer = null;

export function registerChatIpc() {

  // 封裝 SSE 連接邏輯
  async function connectSSE(sender, isFirstAttempt = false) {
    if (intentionalShutdown) return;

    // 如果已經有流在運行，先清理
    if (currentStream) {
      try { currentStream.destroy(); } catch(e) {}
      currentStream = null;
    }

    console.log(`[SSE] ${isFirstAttempt ? 'Starting' : 'Reconnecting'} stream connection...`);

    try {
      // 根據新接口：GET /api/luna/status/stream
      const response = await http.get("/api/luna/status/stream", {
        headers: {
          'Accept': 'text/event-stream',
          'Cache-Control': 'no-cache',
          'Connection': 'keep-alive',
          'X-Accel-Buffering': 'no', // 防止 Nginx 緩衝 SSE 流
        },
        responseType: 'stream'
      });

      const stream = response.data;
      currentStream = stream;
      let buffer = "";

      console.log("[SSE] Connected successfully.");

      // 監聽數據流
      stream.on('data', (chunk) => {
        buffer += chunk.toString();
        console.log("[SSE] Received chunk:", chunk.toString());
        
        // SSE 消息通常以雙換行符分隔
        const parts = buffer.split('\n\n');
        // 保留最後一個可能不完整的部分
        buffer = parts.pop();

        parts.forEach(part => {
          const lines = part.split('\n');
          let eventName = null;
          let dataStr = "";

          // 解析 SSE 協議字段
          lines.forEach(line => {
            if (line.startsWith('event:')) {
              eventName = line.substring(6).trim();
            } else if (line.startsWith('data:')) {
              dataStr += line.substring(5).trim();
            }
          });

          if (dataStr) {
            console.log(`[SSE] Received Event: ${eventName || 'default'}, Data: ${dataStr}`);
            
            // 後端發送的是 event: luna-status
            // 我們可以根據 eventName 做特殊處理，目前統一發送給前端
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
        console.error("[SSE] Stream Error:", err);
        currentStream = null;
        scheduleReconnect(sender);
      });

      stream.on('end', () => {
        console.log("[SSE] Stream ended by server.");
        currentStream = null;
        scheduleReconnect(sender);
      });

    } catch (error) {
      console.error("[SSE] Connection failed:", error);
      if (isFirstAttempt) {
        // 第一次失敗直接拋出，讓前端知道啟動失敗
        throw error;
      } else {
        // 後續斷線則嘗試重連
        scheduleReconnect(sender);
      }
    }
  }

  // 調度重連
  function scheduleReconnect(sender) {
    if (intentionalShutdown) return;
    if (reconnectTimer) return; // 已經在等待重連中

    console.log("[SSE] Scheduling reconnect in 3s...");
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connectSSE(sender);
    }, 3000);
  }

  // === 启动 ===
  ipcMain.handle("luna.api.chat.startup", async (event) => {
    intentionalShutdown = false;
    
    // 清理之前的定時器
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    
    // 第一次連接，如果失敗會拋出異常給前端
    await connectSSE(event.sender, true);
    return { status: "connected" };
  });

  // === 聊天 ===
  ipcMain.handle("luna.api.chat.message", async (_, payload) => {
    return http.post("/luna/api/chat/message", payload);
  });

  // === 关闭 ===
  ipcMain.handle("luna.api.chat.shutdown", async () => {
    console.log("[SSE] Shutdown requested.");
    intentionalShutdown = true;
    
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    
    if (currentStream) {
      currentStream.destroy();
      currentStream = null;
    }
    
    // 根據新接口：GET /api/luna/status/disconnect
    return http.get("/api/luna/status/disconnect");
  });
}
