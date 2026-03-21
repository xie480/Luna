# 前端改造更新文档（JWT 鉴权 + jti 会话标识 + 审批链路）
> 适用版本：当前后端 `org.yilena.luna` 最新接口实现  
> 目标：前端完成鉴权升级、会话标识切换、SSE 事件处理、审批交互打通

---

## 1. 变更总览（一定要先看）

本次后端已完成以下关键升级：

1. **鉴权 token 机制升级为 JWT**
    - 登录接口返回 JWT
    - 业务接口需携带 `Authorization: Bearer <token>`

2. **会话标识 sessionId 逻辑升级**
    - 后端不再依赖前端传线程/临时 sessionId
    - 工具审批链路使用 **JWT 的 `jti`** 作为稳定会话标识（由后端从 token 提取）

3. **敏感工具审批链路启用**
    - 触发敏感工具时，后端会通过 SSE 推送 `APPROVAL_REQUEST`
    - 前端需弹窗让用户同意/拒绝，并调用审批接口回传结果

---

## 2. 前端必须修改的点（Checklist）

- [ ] 登录后保存 JWT（建议内存 + 本地存储双写）
- [ ] 所有受保护接口统一加 `Authorization: Bearer <token>`
- [ ] 401 时自动清理登录态并跳转登录页
- [ ] SSE 订阅改造：支持带鉴权头（原生 EventSource 不支持）
- [ ] 监听并处理 `APPROVAL_REQUEST` 事件（弹窗 + 提交审批）
- [ ] 审批提交接口接入 `/mcp/skills/approval`
- [ ] 不再向后端传旧 sessionId（如有历史字段可停止使用）

---

## 3. 接口变更与接入规范

---

### 3.1 登录接口

**接口**
- `POST /auth/login`

**请求体**
```json
{
  "username": "xxx",
  "password": "xxx"
}
```

**响应**
```json
{
  "token": "<JWT字符串>"
}
```

**前端处理建议**
1. 保存 token
2. 后续请求统一注入 Header：
   - `Authorization: Bearer <token>`

---

### 3.2 登出接口

**接口**
- `POST /auth/logout`

**请求头**
- `Authorization: Bearer <token>`

**响应示例**
```json
{
  "message": "已登出"
}
```

> 后端会将该 JWT 的 `jti` 加入失效集合。  
> 前端登出时也要主动清理本地 token。

---

### 3.3 Chat 对话接口（需鉴权）

**接口**
- `POST /luna/api/chat/message`

**请求头**
- `Authorization: Bearer <token>`

**请求体**
```json
{
  "userInput": "你好"
}
```

**响应说明**
- 返回 Luna 回复 JSON（通常是 `emotion/reply`，`thought` 会被后端去除后再返回前端）
- 前端按 `reply` 渲染即可，避免依赖不稳定字段

---

### 3.4 SSE 状态流（需鉴权，重点）

**接口**
- `GET /api/luna/status/stream`

**问题重点**
- 后端拦截器默认要求鉴权
- 浏览器原生 `EventSource` **无法自定义 Authorization Header**
- 因此你哋前端必须用支持 header 的方案，例如：
  - `@microsoft/fetch-event-source`
  - 或自实现 `fetch + ReadableStream` 解析 SSE

**SSE 事件类型**
1. `luna-status`
   - 常规状态推送（THINKING / RETRIEVING / IDLE 等）
2. `APPROVAL_REQUEST`
   - 敏感工具审批请求，payload 是审批任务信息（含 taskId）

---

### 3.5 审批回调接口（前端必须接）

**接口**
- `POST /mcp/skills/approval`

**请求头**
- `Authorization: Bearer <token>`

**请求体**
```json
{
  "taskId": "xxx",
  "approved": true
}
```

`approved` 取值：
- `true`：同意执行
- `false`：拒绝执行

---

## 4. 前端状态流建议（推荐实现）

---

### 4.1 登录态管理

建议维护一个 auth store：
- `token`
- `isAuthenticated`
- `login() / logout()`
- `getAuthHeader()`

示例（伪代码）：
```ts
function getAuthHeader() {
  const token = authStore.token;
  return token ? { Authorization: `Bearer ${token}` } : {};
}
```

---

### 4.2 统一请求拦截

- 所有 API 请求自动带 token
- 收到 401：
  1. 清 token
  2. 跳登录页
  3. 提示「登录已过期，请重新登录」

---

### 4.3 SSE 订阅与审批弹窗流程

建议流程：

1. 用户登录成功后建立 SSE 连接
2. 收到 `luna-status`：更新状态条文案
3. 收到 `APPROVAL_REQUEST`：
   - 弹审批对话框（显示技能名、参数）
   - 用户点同意/拒绝
   - 调用 `/mcp/skills/approval`
   - 显示执行结果

---

## 5. 与旧版前端不兼容点

1. **旧 token 非 JWT 的逻辑全部移除**
2. **旧 sessionId 手动传参逻辑停止使用**
3. **原生 EventSource 直连方式不可继续使用**（因为无鉴权头）
4. 若前端曾把敏感工具直接执行视为同步成功，现在要兼容“待审批中断”的中间状态

---

## 6. 联调验收清单（建议照住逐条测）

- [ ] 登录成功，拿到 JWT
- [ ] Chat 接口带 token 可正常返回；不带 token 返回 401
- [ ] SSE 可建立连接并收到 `luna-status`
- [ ] 触发敏感工具时收到 `APPROVAL_REQUEST`
- [ ] 同意审批后工具成功执行
- [ ] 拒绝审批后返回拒绝结果
- [ ] 登出后原 token 再请求应 401
- [ ] token 过期后自动跳登录

---

## 7. 常见问题（FAQ）

### Q1：为什么我用 EventSource 一直 401？
因为原生 EventSource 不能加 `Authorization`。请改用支持 header 的 SSE 方案。

### Q2：前端还要不要传 sessionId？
唔使。后端会从 JWT 中提取 `jti` 作为稳定会话标识。

### Q3：审批接口为什么需要 taskId？
`taskId` 是后端创建审批任务后的唯一标识，前端需原样回传。

---

## 8. 给前端团队的最终执行建议

优先级建议：

1. **P0**：JWT 接入 + 请求拦截 + 401 处理
2. **P0**：SSE 鉴权改造（替换 EventSource）
3. **P0**：审批弹窗与 `/mcp/skills/approval` 联调
4. **P1**：统一状态管理和异常提示优化

如按以上改造，前后端可完成新鉴权与审批链路闭环。
