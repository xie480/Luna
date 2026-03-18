# MCP 資源管理接口文檔

本文檔描述了 Luna 平台中 MCP (Model Context Protocol) 資源管理的相關接口，用於前端對接工具 (Tool) 和技能 (Skill) 的註冊、查詢、更新與刪除操作。

## 1. 基礎信息

- **Base URL**: `/mcp`
- **Content-Type**: `application/json`

## 2. 接口列表

### 2.1 註冊原子工具 (Register Tool)

註冊一個新的無狀態、同步執行的原子工具。

- **URL**: `/tools`
- **Method**: `POST`
- **Description**: 註冊一個新的 Tool。系統會自動根據名稱和描述生成向量 Embedding 用於後續檢索。

**請求參數 (Body):**

| 字段名 | 類型 | 必填 | 說明 | 示例 |
| :--- | :--- | :--- | :--- | :--- |
| `name` | string | 是 | 工具名稱 (需唯一，供 LLM 決策使用) | `web_search` |
| `description` | string | 是 | 工具描述 (詳細說明用途) | `通用網頁搜索工具，用於查詢實時信息` |
| `version` | string | 否 | 版本號 | `1.0.0` |
| `owner` | string | 否 | 負責人 | `admin` |
| `beanName` | string | 是 | 對應後端 Spring Bean 名稱 | `searchTools` |
| `methodName` | string | 是 | 對應後端方法名稱 | `web_search` |
| `inputSchema` | string | 是 | 參數 JSON Schema (字符串格式) | `{"type":"object","properties":{"query":{"type":"string"}}}` |
| `outputSchema` | string | 否 | 輸出 JSON Schema | `{"type":"string"}` |

**響應示例:**

```json
{
  "id": "tool_123456789",
  "name": "web_search",
  "description": "通用網頁搜索工具...",
  "beanName": "searchTools",
  "methodName": "web_search",
  "inputSchema": "...",
  "embedding": "[0.123, 0.456, ...]",
  "createdAt": "2023-10-27T10:00:00"
}
```

---

### 2.2 更新原子工具 (Update Tool)

更新已存在的原子工具信息。

- **URL**: `/tools`
- **Method**: `PUT`
- **Description**: 更新 Tool 信息。如果修改了 `name` 或 `description`，系統會自動重新生成 Embedding。

**請求參數 (Body):**

| 字段名 | 類型 | 必填 | 說明 |
| :--- | :--- | :--- | :--- |
| `id` | string | 是 | 工具 ID |
| `name` | string | 否 | 新名稱 |
| `description` | string | 否 | 新描述 |
| ... | ... | ... | 其他可更新字段同註冊接口 |

**響應示例:**

同註冊接口響應。

---

### 2.3 刪除原子工具 (Delete Tool)

- **URL**: `/tools/{id}`
- **Method**: `DELETE`
- **Description**: 根據 ID 物理刪除工具。

**請求參數 (Path):**

| 字段名 | 類型 | 必填 | 說明 |
| :--- | :--- | :--- | :--- |
| `id` | string | 是 | 工具 ID |

**響應示例:**

HTTP 200 OK (無 Body)

---

### 2.4 註冊複合技能 (Register Skill)

註冊一個新的支持異步、審批、權限控制的複合技能。

- **URL**: `/skills`
- **Method**: `POST`
- **Description**: 註冊一個新的 Skill。

**請求參數 (Body):**

| 字段名 | 類型 | 必填 | 說明 | 示例 |
| :--- | :--- | :--- | :--- | :--- |
| `name` | string | 是 | 技能名稱 | `export_data` |
| `description` | string | 是 | 技能描述 | `導出用戶數據` |
| `beanName` | string | 是 | Bean 名稱 | `dataExportSkill` |
| `methodName` | string | 是 | 方法名稱 | `execute` |
| `inputSchema` | string | 是 | 參數 Schema | `...` |
| `runMode` | enum | 否 | 執行模式: `SYNC` (同步), `ASYNC` (異步) | `ASYNC` |
| `requiresApproval` | boolean | 否 | 是否需要審批 | `true` |
| `sensitivity` | enum | 否 | 敏感度: `LOW`, `MEDIUM`, `HIGH` | `HIGH` |

**響應示例:**

```json
{
  "id": "skill_987654321",
  "name": "export_data",
  "runMode": "ASYNC",
  "requiresApproval": true,
  "sensitivity": "HIGH",
  ...
}
```

---

### 2.5 更新複合技能 (Update Skill)

- **URL**: `/skills`
- **Method**: `PUT`
- **Description**: 更新 Skill 信息。

**請求參數 (Body):**

需包含 `id` 及其他待更新字段。

---

### 2.6 刪除複合技能 (Delete Skill)

- **URL**: `/skills/{id}`
- **Method**: `DELETE`

---

### 2.7 獲取所有資源 (List All)

- **URL**: `/resources`
- **Method**: `GET`
- **Description**: 獲取系統中所有註冊的 Tool 和 Skill 列表。

**響應示例:**

```json
[
  {
    "id": "tool_1",
    "type": "TOOL",
    "name": "web_search",
    ...
  },
  {
    "id": "skill_1",
    "type": "SKILL",
    "name": "export_data",
    ...
  }
]
```

---

### 2.8 根據 ID 獲取詳情 (Get By ID)

- **URL**: `/resources/{id}`
- **Method**: `GET`

---

### 2.9 語義搜索資源 (Semantic Search)

- **URL**: `/search`
- **Method**: `POST`
- **Description**: 根據自然語言 Query，通過向量檢索匹配最相關的工具和技能。

**請求參數 (Body):**

| 字段名 | 類型 | 必填 | 說明 | 示例 |
| :--- | :--- | :--- | :--- | :--- |
| `query` | string | 是 | 用戶輸入的自然語言 | `幫我查一下今天的天氣` |

**響應示例:**

返回一個 Resource 列表，按相關度排序。

```json
[
  {
    "id": "tool_weather",
    "name": "weather_query",
    "description": "查詢天氣信息...",
    "type": "TOOL"
  }
]
```

## 3. 枚舉值說明

### ResourceType
- `TOOL`: 原子工具
- `SKILL`: 複合技能

### RunMode
- `SYNC`: 同步執行
- `ASYNC`: 異步執行

### Sensitivity
- `LOW`: 低敏感度 (默認)
- `MEDIUM`: 中敏感度
- `HIGH`: 高敏感度 (通常會被安全網關攔截)
