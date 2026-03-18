# Luna v2.0 架構設計方案：企業級 MCP 平台

## 1. 項目背景與目標

當前 Luna 項目是一個基於 Spring Boot 的單體應用，具備基礎的 LLM 對話、RAG 檢索及硬編碼的工具調用能力（如搜索、記憶、日程）。

**v2.0 升級目標**：
根據 `need.txt` 需求，將項目重構為**多模組 Maven 工程**，引入 **MCP (Model Context Protocol)** 標準，實現「廠商級 Tool Calling + Skill 編排 + 權限管控」平台。核心在於將「工具的定義」與「工具的執行」解耦，並引入標準化的註冊、發現與審批機制。

## 2. 項目工程結構 (Maven Multi-Module)

項目將拆分為以下 7 個核心模組：

