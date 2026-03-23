# OpenClaw MVP 运行手册（最小闭环）

## 目标
验证以下最小闭环可用：

1. 创建并执行计划  
2. 执行阶段节点并推送 SSE 事件  
3. 无论成功/失败都生成 HTML 报告并尝试打开浏览器  

---

## 1. 前置条件

1. 服务已正常启动（含数据库、Redis、RocketMQ）
2. 已完成认证登录，拿到 JWT token
3. 已存在 `openclaw_orchestration_schema.sql` 对应表结构
4. 前端或测试工具可订阅 SSE：`/api/luna/status/stream`

---

## 2. 接口清单

### 2.1 创建并执行计划（推荐主入口）
- `POST /luna/api/plan/run`

请求示例：
```json
{
  "sessionId": "optional-session",
  "userGoal": "帮我做一个最小计划并生成报告"
}
```

说明：
- 后端会优先使用 JWT jti 作为稳定 `sessionId`
- `sessionId` 仅作为兜底

---

### 2.2 执行单阶段
- `POST /luna/api/plan/phase/run`

请求示例：
```json
{
  "planId": "plan-123456",
  "phaseId": "phase-1"
}
```

---

### 2.3 收尾并生成报告
- `POST /luna/api/plan/report/finalize`

请求示例：
```json
{
  "planId": "plan-123456"
}
```

---

## 3. SSE 事件观察点

MVP 期间建议至少观察以下事件：

- `PLAN_CREATED`
- `PLAN_PHASE_STARTED`
- `PLAN_NODE_RUNNING`
- `PLAN_NODE_SUCCESS`
- `PLAN_NODE_FAILED`
- `PLAN_PHASE_FINISHED`
- `PLAN_REPORT_READY`

---

## 4. 报告输出路径

默认输出目录：
- `./data/reports/{planId}.html`

若当前环境支持 `Desktop`，会尝试自动打开浏览器展示报告。

---

## 5. 验收标准（MVP）

1. `/luna/api/plan/run` 能返回结构化结果（含 `planId`、`phaseResult`、`reportResult`）
2. 执行过程中可以收到关键 SSE 事件
3. 最终能够生成 HTML 报告文件
4. 节点状态流转符合最小规则（PENDING/BLOCKED/APPROVAL_PENDING -> RUNNING -> SUCCESS/FAILED）

---

## 6. 常见问题

### Q1：返回 `PHASE_EMPTY`
- 原因：该阶段下没有节点
- 处理：检查计划初始化逻辑是否成功写入节点

### Q2：报告未自动打开
- 原因：服务器环境不支持 `Desktop`
- 处理：直接打开 `./data/reports/{planId}.html` 查看

### Q3：SSE 没收到事件
- 检查是否已连接 `/api/luna/status/stream`
- 检查客户端ID是否为默认 `default`
- 检查日志中 `emit_plan_event_sse` 是否报错
