# Luna 前端接口文档（统一版）

> 适用后端：当前 `org.yilena.luna` 代码  
> 默认服务地址：`http://localhost:8001`（按实际环境替换）

---

## 1. 鉴权说明

- 除 `/auth/login` 及文档相关接口外，其余接口都需要 JWT。
- 请求头统一：
  - `Authorization: Bearer <token>`
  - `Content-Type: application/json`

### 1.1 登录

- **POST** `/auth/login`
- 请求体：
```json
{
  "username": "Yilena",
  "password": "XUWENBO219382"
}
```
- 响应：
```json
{
  "token": "eyJhbGciOi..."
}
```

### 1.2 登出

- **POST** `/auth/logout`
- Header：`Authorization: Bearer <token>`
- 响应：
```json
{
  "message": "已登出"
}
```

---

## 2. 对话接口（Chat）

### 2.1 普通对话

- **POST** `/luna/api/chat/message`
- 请求体：
```json
{
  "userInput": "你好，今天广州天气如何？"
}
```
- 正常响应（示例）：
```json
{
  "emotion": "Soft",
  "reply": "……广州今天有点热，记得补水。"
}
```

- 若触发敏感工具审批，会先返回：
```json
{
  "status": "pending_approval",
  "message": "操作需要审批，请在前端确认"
}
```
并通过 SSE 下发 `APPROVAL_REQUEST` 事件。

---

### 2.2 开机

- **POST** `/luna/api/chat/startup`
- 响应格式同 chat（`emotion` + `reply`）。

### 2.3 关机

- **POST** `/luna/api/chat/shutdown`
- 响应：HTTP 200，无 body。

### 2.4 查询有历史的日期

- **GET** `/luna/api/chat/history/date?ym=2026:03`
- 响应（示例）：
```json
["20", "21", "22"]
```

### 2.5 查询某日历史

- **GET** `/luna/api/chat/history?ymd=2026:03:21`
- 响应（示例）：
```json
[
  "USER:你好:09:00:00",
  "LUNA:早安，主人。:09:00:01"
]
```

---

## 3. SSE 实时状态接口

> 注意：浏览器原生 `EventSource` 不能带 Authorization Header。  
> 前端请使用 `fetch-event-source` 或自实现 `fetch + ReadableStream` 解析 SSE。

### 3.1 建立状态流连接

- **GET** `/api/luna/status/stream`
- Header：`Authorization: Bearer <token>`

### 3.2 主动断开

- **GET** `/api/luna/status/disconnect`

### 3.3 SSE 事件类型

1. `luna-status`  
   常规状态推送，如：
   - `THINKING`
   - `RETRIEVING`
   - `PENDING_APPROVAL`
   - `IDLE`

2. `APPROVAL_REQUEST`  
   触发敏感操作后的审批请求，包含任务信息（重点 `taskId`）。

3. `APPROVAL_RESULT`  
   审批处理完成结果（同意/拒绝后续跑 chat 的结果）。

4. `SKILL_ASYNC_RESULT`  
   异步技能执行完成通知（成功/失败）。

---

## 4. 审批接口

### 4.1 提交审批结果

- **POST** `/mcp/skills/approval`
- 请求体：
```json
{
  "taskId": "cb4cb7d9-6a0b-418a-8ed7-3ef642674830",
  "approved": true
}
```
- 响应：JSON（通常包含 `emotion/reply`，或错误结构）

---

## 5. MCP 资源管理接口

### 5.1 Tool 管理
- **POST** `/mcp/tools`（注册）
- **PUT** `/mcp/tools`（更新）
- **DELETE** `/mcp/tools/{id}`（删除）

### 5.2 Skill 管理
- **POST** `/mcp/skills`（注册）
- **PUT** `/mcp/skills`（更新）
- **DELETE** `/mcp/skills/{id}`（删除）

### 5.3 资源查询
- **GET** `/mcp/resources`（全部资源）
- **GET** `/mcp/resources/{id}`（按 ID 查）
- **POST** `/mcp/search`（语义检索）
```json
{
  "query": "帮我搜索广州天气"
}
```

---

## 6. 业务分页查询接口

统一返回结构：
```json
{
  "total": 100,
  "pages": 10,
  "pageNo": 1,
  "pageSize": 10,
  "records": []
}
```

---

### 6.1 知识库分页

- **POST** `/luna/api/query/knowledge-base`  
  或 `/luna/api/query/knowledge-base/page`

请求体字段：
- `pageNo` Long
- `pageSize` Long
- `title` String（模糊）
- `content` String（模糊）
- `sourceType` String：`FILE | WEB_SEARCH | MANUAL_INPUT`
- `sourcePath` String（模糊）
- `startTime` String：`yyyy-MM-dd HH:mm:ss`
- `endTime` String：`yyyy-MM-dd HH:mm:ss`

---

### 6.2 用户偏好分页

- **POST** `/luna/api/query/user-preference`  
  或 `/luna/api/query/user-preference/page`

请求体字段：
- `pageNo`
- `pageSize`
- `prefKey`（模糊）
- `prefValue`（模糊）
- `description`（模糊）
- `startTime`
- `endTime`

---

### 6.3 长期记忆分页

- **POST** `/luna/api/query/memory`  
  或 `/luna/api/query/memory/page`

请求体字段：
- `pageNo`
- `pageSize`
- `sessionId`
- `memoryType`：`FACT | PREFERENCE | SUMMARY | REFLECTION`
- `content`（模糊）
- `minWeight` Integer
- `maxWeight` Integer
- `startTime`
- `endTime`

---

### 6.4 日志分页

- **POST** `/luna/api/query/log`  
  或 `/luna/api/query/log/page`

请求体字段：
- `pageNo`
- `pageSize`
- `logType`：`LUNA_OUTPUT | TOOL_CALL | ERROR | SELF_UPDATE | SYSTEM_EVENT | API_CALL`
- `module`（模糊）
- `action`（模糊）
- `content`（模糊）
- `traceId`
- `operatorId`
- `startTime`
- `endTime`

---

## 7. 常见错误码与前端处理建议

### 7.1 401 未授权
可能响应格式 1：
```json
{
  "error": "未授权，请先登录"
}
```
可能响应格式 2：
```json
{
  "status": "unauthorized",
  "message": "token无效或已过期"
}
```
建议前端统一：
1. 清除 token
2. 跳转登录
3. Toast 提示“登录已过期，请重新登录”

### 7.2 参数错误（400）
```json
{
  "status": "error",
  "message": "参数错误: ..."
}
```

### 7.3 审批中断（200 + pending_approval）
```json
{
  "status": "pending_approval",
  "message": "操作需要审批，请在前端确认"
}
```
前端需等待 SSE 的 `APPROVAL_REQUEST` 并弹窗处理。

---

## 8. 前端落地最小清单

- [ ] 全局请求拦截器注入 Bearer Token
- [ ] 401 统一回收登录态
- [ ] SSE 改为可带 Header 的实现
- [ ] 监听 `APPROVAL_REQUEST`、`APPROVAL_RESULT`、`SKILL_ASYNC_RESULT`
- [ ] 对接 `/mcp/skills/approval`
- [ ] 分页查询页统一使用四个业务接口

---
如需，我可以再补一份「前端 TypeScript 类型定义 + Axios 封装 + SSE 示例代码」。
