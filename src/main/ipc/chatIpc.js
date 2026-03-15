import { ipcMain } from "electron";
import http from "../httpClient.js";

export function registerChatIpc() {

  // === 启动 ===
  ipcMain.handle("luna.api.chat.startup", async (event) => {
    try {
      // SSE 订阅请求：需要设置 Accept 头，并以流的方式接收
      const response = await http.post("/luna/api/chat/startup", {}, {
        headers: {
          'Accept': 'text/event-stream',
          'Cache-Control': 'no-cache',
          'Connection': 'keep-alive',
        },
        responseType: 'stream'
      });

      const stream = response.data;
      let buffer = "";

      // 监听数据流
      stream.on('data', (chunk) => {
        buffer += chunk.toString();
        
        // SSE 消息通常以双换行符分隔
        const parts = buffer.split('\n\n');
        // 保留最后一个可能不完整的部分
        buffer = parts.pop();

        parts.forEach(part => {
          const lines = part.split('\n');
          lines.forEach(line => {
            if (line.startsWith('data:')) {
              const dataStr = line.substring(5).trim();
              if (dataStr) {
                try {
                  // 尝试解析 JSON
                  const data = JSON.parse(dataStr);
                  event.sender.send('luna:status-update', data);
                } catch (e) {
                  // 如果不是 JSON，直接发送字符串
                  event.sender.send('luna:status-update', dataStr);
                }
              }
            }
          });
        });
      });

      stream.on('error', (err) => {
        console.error("SSE Stream Error:", err);
      });

      // 连接建立成功
      return { status: "connected" };

    } catch (error) {
      console.error("Failed to start chat session:", error);
      throw error;
    }
  });

  // === 聊天 ===
  ipcMain.handle("luna.api.chat.message", async (_, payload) => {
    return http.post("/luna/api/chat/message", payload);
  });

  // === 关闭 ===
  ipcMain.handle("luna.api.chat.shutdown", async () => {
    return http.post("/luna/api/chat/shutdown");
  });
}
