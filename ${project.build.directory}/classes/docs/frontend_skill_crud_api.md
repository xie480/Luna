# Skill 增删改查接口文档（前端对接）

## 1. 说明

本文档基于当前后端代码实际实现（`org.yilena.luna.controller.McpController`），用于前端对接 Skill（复合技能）管理能力。

- 基础路径：`/mcp`
- 数据格式：`application/json`
- 统一说明：
  - Skill 与 Tool 在后端统一抽象为 Resource
  - Skill 的 `id` 为 Long（雪花 ID）
  - Skill 的查询接口目前通过 `/mcp/resources` 统一获取后由前端过滤 `type=SKILL`

---

## 2. 数据结构（Skill）

请求/响应核心结构（对应 `McpSkill`）：

```json
{
  "id": 0,
  "name": "string",
  "description": "string",
  "version": "1.0.0",
  "owner": "string",
  "beanName": "string",
  "methodName": "string",
  "inputSchema": "string",
  "outputSchema": "string",
  "runMode": "SYNC",
  "requiredCapabilities": ["WEB_SEARCH", "WEB_FETCH"],
  "toolSlots": [
    {
      "slot": "search",
      "capability": "WEB_SEARCH",
      "required": true
    }
  ],
  "thoughtChain": ["先搜索", "再抓取", "最后总结"],
  "embedding": "string",
  "createdAt": "2026-01-01T00:00:00",
  "updatedAt": "2026-01-01T00:00:00"
}
```

### 字段说明

- `id`: 技能 ID（更新/删除时必须）
- `name`: 技能唯一名称（建议前端做唯一性提示）
- `description`: 技能描述
- `version`: 版本号
- `owner`: 负责人
- `beanName`: Spring Bean 名称
- `methodName`: 执行方法名
- `inputSchema`: 输入参数 JSON Schema（字符串）
- `outputSchema`: 输出参数 JSON Schema（字符串）
- `runMode`: 执行模式，枚举：
  - `SYNC`：同步执行
  - `ASYNC`：异步执行
- `requiredCapabilities`: 技能所需能力集合（字符串数组）
- `toolSlots`: 能力槽位定义（数组）
  - `slot`: 槽位名
  - `capability`: 对应能力名
  - `required`: 是否必填
- `thoughtChain`: 编排思维链（字符串数组）
- `embedding`: 向量字段（一般由后端生成，不建议前端手动传）
- `createdAt` / `updatedAt`: 时间字段（后端生成）

---

## 3. 接口列表

---

### 3.1 新增 Skill

- **URL**：`POST /mcp/skills`
- **描述**：注册一个新的复合技能
- **请求体**：`McpSkill` JSON
- **响应体**：`McpSkill` JSON（后端落库后的对象）

#### 请求示例

```http
POST /mcp/skills
Content-Type: application/json
```

```json
{
  "name": "skill_search_ingest_kb",
  "description": "搜索并写入知识库",
  "version": "1.0.0",
  "owner": "luna-team",
  "beanName": "skillExecutor",
  "methodName": "execute",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}",
  "outputSchema": "{\"type\":\"object\"}",
  "runMode": "ASYNC",
  "requiredCapabilities": ["WEB_SEARCH", "WEB_SCRAPE", "KB_INSERT"],
  "toolSlots": [
    {"slot":"search","capability":"WEB_SEARCH","required":true},
    {"slot":"scrape","capability":"WEB_SCRAPE","required":true},
    {"slot":"insert","capability":"KB_INSERT","required":true}
  ],
  "thoughtChain": ["先搜索候选结果", "抓取网页正文", "写入知识库"]
}
```

#### 响应示例

```json
{
  "id": 1930000000000000001,
  "name": "skill_search_ingest_kb",
  "description": "搜索并写入知识库",
  "version": "1.0.0",
  "owner": "luna-team",
  "beanName": "skillExecutor",
  "methodName": "execute",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}",
  "outputSchema": "{\"type\":\"object\"}",
  "runMode": "ASYNC",
  "requiredCapabilities": ["WEB_SEARCH", "WEB_SCRAPE", "KB_INSERT"],
  "toolSlots": [
    {"slot":"search","capability":"WEB_SEARCH","required":true},
    {"slot":"scrape","capability":"WEB_SCRAPE","required":true},
    {"slot":"insert","capability":"KB_INSERT","required":true}
  ],
  "thoughtChain": ["先搜索候选结果", "抓取网页正文", "写入知识库"],
  "embedding": "[...]",
  "createdAt": "2026-03-22T10:00:00",
  "updatedAt": "2026-03-22T10:00:00"
}
```

---

### 3.2 更新 Skill

- **URL**：`PUT /mcp/skills`
- **描述**：更新已存在的复合技能
- **请求体**：`McpSkill` JSON（必须带 `id`）
- **响应体**：`McpSkill` JSON（更新后的对象）

#### 请求示例

```http
PUT /mcp/skills
Content-Type: application/json
```

```json
{
  "id": 1930000000000000001,
  "name": "skill_search_ingest_kb",
  "description": "搜索后清洗并写入知识库",
  "runMode": "SYNC",
  "thoughtChain": ["先搜索", "清洗正文", "写入知识库并返回摘要"]
}
```

#### 响应示例

```json
{
  "id": 1930000000000000001,
  "name": "skill_search_ingest_kb",
  "description": "搜索后清洗并写入知识库",
  "version": "1.0.0",
  "owner": "luna-team",
  "beanName": "skillExecutor",
  "methodName": "execute",
  "inputSchema": "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}",
  "outputSchema": "{\"type\":\"object\"}",
  "runMode": "SYNC",
  "requiredCapabilities": ["WEB_SEARCH", "WEB_SCRAPE", "KB_INSERT"],
  "toolSlots": [
    {"slot":"search","capability":"WEB_SEARCH","required":true},
    {"slot":"scrape","capability":"WEB_SCRAPE","required":true},
    {"slot":"insert","capability":"KB_INSERT","required":true}
  ],
  "thoughtChain": ["先搜索", "清洗正文", "写入知识库并返回摘要"],
  "embedding": "[...]",
  "createdAt": "2026-03-22T10:00:00",
  "updatedAt": "2026-03-22T10:30:00"
}
```

---

### 3.3 删除 Skill

- **URL**：`DELETE /mcp/skills/{id}`
- **描述**：按 ID 删除复合技能
- **路径参数**：
  - `id`：Skill ID（Long）
- **响应体**：空（HTTP 200）

#### 请求示例

```http
DELETE /mcp/skills/1930000000000000001
```

#### 响应示例

HTTP 200（无 body）

---

### 3.4 查询 Skill 列表（当前实现）

> 当前后端没有独立 `GET /mcp/skills`，统一走资源查询接口。

- **URL**：`GET /mcp/resources`
- **描述**：获取全部资源（Tool + Skill）
- **前端处理**：按 `type === "SKILL"` 过滤

#### 请求示例

```http
GET /mcp/resources
```

#### 响应示例（节选）

```json
[
  {
    "id": "1930000000000000001",
    "type": "SKILL",
    "name": "skill_search_ingest_kb",
    "description": "搜索并写入知识库",
    "runMode": "ASYNC",
    "requiredCapabilities": ["WEB_SEARCH", "WEB_SCRAPE", "KB_INSERT"],
    "toolSlots": [
      {"slot":"search","capability":"WEB_SEARCH","required":true}
    ],
    "thoughtChain": ["先搜索候选结果", "抓取网页正文", "写入知识库"]
  },
  {
    "id": "1930000000000000100",
    "type": "TOOL",
    "name": "web_search",
    "description": "执行网页搜索"
  }
]
```

---

### 3.5 查询 Skill 详情（当前实现）

> 当前后端没有独立 `GET /mcp/skills/{id}`，统一走资源详情接口。

- **URL**：`GET /mcp/resources/{id}`
- **描述**：按 ID 获取资源详情（可能是 Tool 或 Skill）
- **前端处理**：校验 `type === "SKILL"`

#### 请求示例

```http
GET /mcp/resources/1930000000000000001
```

#### 响应示例

```json
{
  "id": "1930000000000000001",
  "type": "SKILL",
  "name": "skill_search_ingest_kb",
  "description": "搜索并写入知识库",
  "version": "1.0.0",
  "owner": "luna-team",
  "beanName": "skillExecutor",
  "methodName": "execute",
  "inputSchema": "{\"type\":\"object\"}",
  "outputSchema": "{\"type\":\"object\"}",
  "runMode": "ASYNC",
  "requiresApproval": false,
  "sensitivity": "LOW",
  "requiredCapabilities": ["WEB_SEARCH", "WEB_SCRAPE", "KB_INSERT"],
  "toolSlots": [
    {"slot":"search","capability":"WEB_SEARCH","required":true},
    {"slot":"scrape","capability":"WEB_SCRAPE","required":true},
    {"slot":"insert","capability":"KB_INSERT","required":true}
  ],
  "thoughtChain": ["先搜索候选结果", "抓取网页正文", "写入知识库"]
}
```

---

## 4. 前端对接建议

1. **列表页**
   - 调用 `GET /mcp/resources`
   - 前端筛选 `type === "SKILL"`

2. **详情页**
   - 调用 `GET /mcp/resources/{id}`
   - 判断 `type` 是否为 `SKILL`

3. **表单提交**
   - 新增：`POST /mcp/skills`
   - 编辑：`PUT /mcp/skills`（必须带 `id`）
   - 删除：`DELETE /mcp/skills/{id}`

4. **枚举约束**
   - `runMode`：`SYNC | ASYNC`

5. **字段兼容**
   - `id` 在资源接口中为字符串（`Resource.id`），在新增/更新 Skill 请求中按数字传也可由后端处理

---

## 5. 错误处理建议

- 4xx：请求参数问题（如缺少 `id`、JSON 格式错误）
- 5xx：服务内部错误
- 前端建议统一 toast 展示 `message`（若后端返回）

常见失败场景：
- 更新时 `id` 为空
- `name` 重复导致唯一键冲突
- `runMode` 非法值（非 `SYNC/ASYNC`）
