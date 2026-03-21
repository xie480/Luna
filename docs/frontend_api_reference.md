# Luna 前端接口文档（仅分页查询接口）

> 适用范围：仅包含本次新增的四个分页条件查询接口  
> 基础地址：`http://localhost:8001`（按实际环境替换）

---

## 1. 鉴权说明

这四个接口都需要 JWT 鉴权，请在请求头携带：

- `Authorization: Bearer <token>`
- `Content-Type: application/json`

---

## 2. 通用约定

### 2.1 分页请求公共字段

所有分页接口请求体都支持以下字段：

- `pageNo`：页码，从 1 开始（默认 1）
- `pageSize`：每页条数，最大 200（默认 10）

### 2.2 通用分页响应结构

```json
{
  "total": 100,
  "pages": 10,
  "pageNo": 1,
  "pageSize": 10,
  "records": []
}
```

### 2.3 时间字段格式

若接口支持时间过滤，格式统一为：

- `yyyy-MM-dd HH:mm:ss`

---

## 3. 分页查询接口

---

### 3.1 知识库分页查询

- **POST** `/luna/api/query/knowledge-base`
- 兼容路径：`/luna/api/query/knowledge-base/page`

#### 请求体

```json
{
  "pageNo": 1,
  "pageSize": 10,
  "title": "天气",
  "content": "广州",
  "sourceType": "WEB_SEARCH",
  "sourcePath": "weather.com",
  "startTime": "2026-03-20 00:00:00",
  "endTime": "2026-03-21 23:59:59"
}
```

#### 字段说明

- `title`：标题模糊查询
- `content`：内容模糊查询
- `sourceType`：来源类型，枚举：
  - `FILE`
  - `WEB_SEARCH`
  - `MANUAL_INPUT`
- `sourcePath`：来源路径模糊查询
- `startTime` / `endTime`：按 `createdAt` 时间范围过滤

---

### 3.2 用户偏好分页查询

- **POST** `/luna/api/query/user-preference`
- 兼容路径：`/luna/api/query/user-preference/page`

#### 请求体

```json
{
  "pageNo": 1,
  "pageSize": 10,
  "prefKey": "language",
  "prefValue": "zh",
  "description": "回复风格",
  "startTime": "2026-03-20 00:00:00",
  "endTime": "2026-03-21 23:59:59"
}
```

#### 字段说明

- `prefKey`：偏好键模糊查询
- `prefValue`：偏好值模糊查询
- `description`：描述模糊查询
- `startTime` / `endTime`：按 `createdAt` 时间范围过滤

---

### 3.3 长期记忆分页查询

- **POST** `/luna/api/query/memory`
- 兼容路径：`/luna/api/query/memory/page`

#### 请求体

```json
{
  "pageNo": 1,
  "pageSize": 10,
  "sessionId": "2026:03:21",
  "memoryType": "SUMMARY",
  "content": "天气",
  "minWeight": 1,
  "maxWeight": 5,
  "startTime": "2026-03-20 00:00:00",
  "endTime": "2026-03-21 23:59:59"
}
```

#### 字段说明

- `sessionId`：会话 ID 精确查询
- `memoryType`：记忆类型，枚举：
  - `FACT`
  - `PREFERENCE`
  - `SUMMARY`
  - `REFLECTION`
- `content`：记忆内容模糊查询
- `minWeight` / `maxWeight`：权重范围过滤
- `startTime` / `endTime`：按 `createdAt` 时间范围过滤

---

### 3.4 日志分页查询

- **POST** `/luna/api/query/log`
- 兼容路径：`/luna/api/query/log/page`

#### 请求体

```json
{
  "pageNo": 1,
  "pageSize": 20,
  "logType": "ERROR",
  "module": "chat",
  "action": "chat",
  "content": "审批",
  "traceId": "xxx-yyy-zzz",
  "operatorId": "u1001",
  "startTime": "2026-03-20 00:00:00",
  "endTime": "2026-03-21 23:59:59"
}
```

#### 字段说明

- `logType`：日志类型，枚举：
  - `LUNA_OUTPUT`
  - `TOOL_CALL`
  - `ERROR`
  - `SELF_UPDATE`
  - `SYSTEM_EVENT`
  - `API_CALL`
- `module`：模块模糊查询
- `action`：动作模糊查询
- `content`：内容模糊查询
- `traceId`：链路 ID 精确查询
- `operatorId`：操作人 ID 精确查询
- `startTime` / `endTime`：按 `createAt` 时间范围过滤

---

## 4. 错误响应示例

### 4.1 参数错误（400）

```json
{
  "status": "error",
  "message": "参数错误: No enum constant ..."
}
```

### 4.2 鉴权失败（401）

```json
{
  "error": "未授权，请先登录"
}
```

---
如需，我可以再帮你补一份「只包含这四个接口的前端 TypeScript 类型定义（Request/Response）」。
