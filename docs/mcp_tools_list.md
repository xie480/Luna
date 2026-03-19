# MCP 工具註冊清單

以下是根據現有代碼庫 (`src/main/java/org/yilena/luna/tools/`) 生成的 MCP 工具定義。
這些 JSON 對象對應後端 `McpTool` 實體結構。

> **注意**：`input_schema` 字段在數據庫實體中定義為 `String` 類型。如果你是通過 API (Postman/Axios) 直接發送請求，請確保將 `input_schema` 的值轉換為 JSON 字符串，或者在前端代碼中進行 `JSON.stringify()` 處理。

---

## 1. 用戶偏好設置 (PreferenceTools)

**功能**：管理用戶的個性化設置（增刪改查）。

