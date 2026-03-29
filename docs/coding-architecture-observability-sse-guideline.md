# Luna 编码规范与架构/可观测/SSE 发布指南

> 适用范围：Luna 全仓库（后端 Java、Python 推理服务、编排执行链路、前端联调事件协议）  
> 目标：统一编码规范、架构落地边界、日志审计要求，以及关键阶段 SSE publish 行为，确保系统**可维护、可审计、可恢复、可回放**。

---

## 1. 文档定位与原则

本规范基于 `agent.md` 与 `README.md` 的项目定义整理，属于工程强约束文档。  
当出现规范冲突时，按以下优先级执行：

1. **已上线稳定契约（API/SSE/EventType）**
2. **本规范**
3. `agent.md`
4. `README.md`
5. 其他说明文档

核心原则：

- 先契约后实现
- 先可观测后优化
- 先恢复能力后扩展能力
- 先安全审计后自动执行

---

## 2. 架构设计与分层编码规范

### 2.1 分层边界（必须）

遵循以下分层，不得跨层乱调：

- **Controller 层**：参数接收、鉴权校验、trace 注入、响应封装（禁止业务编排）
- **Service 层**：业务编排、事务边界、状态机流转、失败补偿
- **Executor/Tool/Skill 层**：原子动作执行、外部调用、结果归一化
- **Mapper/Repository 层**：数据读写，不承载业务规则
- **MQ/SSE/Event 层**：异步事件发布与订阅，保证幂等与可追踪

禁止项：

1. Controller 直接调 Mapper  
2. Tool 直接操作前端推送通道（必须通过统一事件服务）  
3. 状态迁移绕过状态机校验  
4. 在业务代码散落 eventType/status/errorCode 魔法字符串

### 2.2 OpenClaw 编排约束（必须）

围绕 Plan/Phase/Node：

1. Plan 创建后需冻结蓝图（Blueprint Freeze）
2. Phase/Node 状态迁移必须合法（如：PENDING -> RUNNING -> SUCCESS/FAILED）
3. 每个失败节点必须记录 retryCount、failReason、errorCode、costMs
4. 支持 replan 时必须保留原计划与补丁计划关系（version/parentPlanId）
5. 关键节点必须可恢复（checkpoint + ledger + snapshot）

### 2.3 CodeOps 闭环约束（必须）

针对读码、改码、测试、修复、报告链路：

1. 补丁应用前必须 dry-run
2. build/test/lint/format 结果必须结构化落库或落日志
3. 自动修复循环必须有最大重试次数与熔断条件
4. 回滚动作必须可追踪（触发人/触发策略/回滚范围）
5. 报告输出必须包含变更摘要、测试结果、风险提示

---

## 3. 编码强制规则（开发执行清单）

### 3.1 契约优先

新增功能前，必须先定义：

- inputSchema
- outputSchema
- errorCode 列表
- eventType 列表
- SSE payload 字段（向后兼容）

### 3.2 常量集中管理

以下内容必须集中在常量或枚举中统一管理：

- EventType（如 PLAN_CREATED、PHASE_STARTED）
- Status（RUNNING/SUCCESS/FAILED/APPROVAL_PENDING 等）
- ErrorCode（超时、参数错误、依赖故障、审批拒绝等）
- Topic/Key/Channel 名称

### 3.3 失败优先设计

每个外部依赖（LLM、DB、Redis、HTTP、子进程、MQ）必须有：

- timeout
- retry（指数退避）
- fallback（降级路径）
- 审计日志（含失败原因）

### 3.4 可恢复优先

中断恢复能力必须覆盖：

- 审批中断恢复
- 服务重启恢复
- 阶段失败后重试恢复
- 局部 replan 后继续执行

---

## 4. 日志与审计规范（新增/完善重点）

### 4.1 日志等级与用途

- **INFO**：关键业务节点进展（开始/完成/发布事件）
- **WARN**：可恢复异常（重试、降级、超时）
- **ERROR**：不可恢复异常（任务中断、数据不一致、状态机非法迁移）
- **AUDIT（逻辑级，可映射 INFO）**：审批、敏感动作、回滚、计划变更等审计行为

### 4.2 结构化日志字段（必须）

所有关键链路日志必须包含（最少）：

- `traceId`
- `planId`（有编排任务时必填）
- `phaseId`（阶段内必填）
- `nodeId`（节点内必填）
- `sessionId` / `userId`（可用时）
- `eventType`
- `status`
- `costMs`
- `retryCount`
- `errorCode`（失败时）
- `failReason`（失败时，注意脱敏）

### 4.3 关键行为必打日志清单

1. Plan 创建、冻结、启动、完成、失败
2. Phase 启动、完成、失败
3. Skill 调用开始/结束（输入输出摘要，不打敏感原文）
4. Tool 实际执行结果（命令类需额外记录白名单命中情况）
5. 审批请求创建、推送、回调、拒绝、超时、恢复执行
6. Replan 触发原因、重规划结果、补丁应用结果
7. 回滚触发、回滚结果
8. SSE 发布成功/失败（失败要记录客户端标识与重试信息）

### 4.4 日志脱敏规范

禁止明文输出：

- token、密钥、密码、cookie
- 用户敏感身份信息
- 本地绝对路径（按需打掩码）
- 完整私密文本内容（只保留摘要或哈希）

---

## 5. SSE 发布规范（关键阶段必须 publish）

### 5.1 统一发布目标

SSE 用于把“可视化执行状态 + 关键消息（msg）”实时推送前端。  
要求做到：

1. 状态单调可理解（前端可驱动状态机）
2. 字段稳定向后兼容
3. 丢事件可通过快照补偿

### 5.2 建议统一 payload 结构

建议后端统一输出 JSON（字段可增不可删，新增字段默认 optional）：

```json
{
  "specVersion": "1.0",
  "eventId": "evt_01J2ABCXYZ",
  "eventSeq": 128,
  "eventType": "PHASE_STARTED",
  "sseTopic": "luna-status",
  "ts": "2026-03-29T09:15:23.456Z",
  "traceId": "trc_8f3d...",
  "sessionId": "sess_123",
  "userId": "u_001",
  "planId": "plan_20260329_001",
  "phaseId": "phase_2",
  "nodeId": "node_2_1",
  "status": "RUNNING",
  "msg": "Phase 2 started",
  "progress": 40,
  "costMs": 0,
  "retryCount": 0,
  "data": {
    "key": "value"
  },
  "error": null
}
```

字段约束：

- `specVersion`：事件协议版本，默认 `1.0`
- `eventId`：全局唯一，支持消费端去重
- `eventSeq`：同一 `planId` 下严格递增，用于回放与断线续传
- `eventType`：业务语义类型，统一枚举管理
- `sseTopic`：SSE 通道名，当前统一为 `luna-status`
- `ts`：UTC ISO-8601 时间戳
- `traceId/planId/phaseId/nodeId`：链路关联字段
- `status`：状态机状态，不允许自由文本
- `msg`：面向前端展示的简述，不承载敏感数据
- `data`：结构化扩展载荷
- `error`：失败时必填，至少包含 `code`、`message`、`retryable`

### 5.3 事件字典最小集合（强制）

以下事件是对 `agent.md` 中 OpenClaw、HITL、CodeOps、Execution Memory Ledger 的最小落地集，新增事件必须向后兼容：

1. Plan 级：`PLAN_CREATED`、`BLUEPRINT_FROZEN`、`PLAN_STARTED`、`PLAN_COMPLETED`、`PLAN_FAILED`
2. Phase/Node 级：`PHASE_STARTED`、`PHASE_COMPLETED`、`PHASE_FAILED`、`NODE_STARTED`、`NODE_RETRYING`、`NODE_COMPLETED`、`NODE_FAILED`
3. Skill/Tool 级：`SKILL_CALLED`、`SKILL_RESULT`、`SKILL_ASYNC_RESULT`
4. 审批链路：`APPROVAL_REQUESTED`、`APPROVAL_APPROVED`、`APPROVAL_REJECTED`、`APPROVAL_TIMEOUT`、`EXECUTION_RESUMED`
5. 重规划链路：`REPLAN_TRIGGERED`、`REPLAN_APPLIED`
6. CodeOps 级：`CODEOPS_PATCH_DRY_RUN`、`CODEOPS_PATCH_APPLIED`、`TEST_RUN_STARTED`、`TEST_RUN_RESULT`、`AUTO_FIX_ROUND`、`ROLLBACK_TRIGGERED`、`ROLLBACK_COMPLETED`、`REPORT_GENERATED`
7. 系统状态：`LUNA_STATUS`（兼容历史 `luna-status` 通道）

### 5.4 发布时序与幂等要求

1. 同一 `planId` 内，`eventSeq` 必须单调递增，不允许乱序回写。
2. 发布语义按“至少一次”设计，消费端必须用 `eventId` 去重。
3. 状态迁移必须合法，禁止直接从 `PENDING` 跳到 `FAILED` 之外的非法状态。
4. 发布失败不得吞错，必须记录 `SSE_PUBLISH_FAILED` 日志并触发重试或降级。
5. 发布与落库必须保持一致性，推荐 Outbox/事务消息模式，避免“只发不存”或“只存不发”。
6. 高频进度事件必须限流（建议单节点每秒不超过 2 条）以保护前端与网络稳定。

### 5.5 快照补偿与断线恢复

1. 以下节点必须产出快照：`PHASE_COMPLETED`、`APPROVAL_REQUESTED`、`REPLAN_APPLIED`、`PLAN_COMPLETED/FAILED`。
2. 客户端重连时携带 `Last-Event-ID` 或 `lastEventSeq`，服务端按序补发增量事件。
3. 若增量缺失或超出保留窗口，服务端必须返回最新快照并发出 `SNAPSHOT_RELOADED` 事件。
4. 前端状态重建采用“快照 + 增量事件”模式，不允许仅依赖内存态。
5. 事件与快照都必须带 `traceId/planId/phaseId`（如适用），保证审计可追溯。

### 5.6 审批链路（HITL）SSE 规则

1. 进入审批时立即发布 `APPROVAL_REQUESTED`，并将节点状态置为 `APPROVAL_PENDING`。
2. 审批结果只允许三类终态：`APPROVAL_APPROVED`、`APPROVAL_REJECTED`、`APPROVAL_TIMEOUT`。
3. 审批通过后必须发布 `EXECUTION_RESUMED`，并恢复原 `traceId/planId/phaseId/nodeId`。
4. 审批拒绝或超时时必须同时写入审计日志，包含审批人（或系统策略）与原因摘要。

### 5.7 重规划与执行记忆账本（Execution Memory Ledger）联动

1. 检测到偏离用户原始命令时，必须发布 `REPLAN_TRIGGERED` 并写入触发原因。
2. 重规划成功后必须发布 `REPLAN_APPLIED`，并记录 `parentPlanId/version`。
3. 账本至少固化以下字段：`immutableOriginalCommand`、`approvedBlueprint`、`currentPhase`、`completedPhases`、`phaseKeyOutputs`。
4. 下一阶段执行前必须加载最新 snapshot + `immutableOriginalCommand`，禁止跳过。

### 5.8 CodeOps 链路 SSE 规则

1. 补丁流程至少发布：`CODEOPS_PATCH_DRY_RUN` -> `CODEOPS_PATCH_APPLIED`。
2. 测试流程至少发布：`TEST_RUN_STARTED` -> `TEST_RUN_RESULT`。
3. 自动修复每轮必须发布 `AUTO_FIX_ROUND`（含轮次、策略、结果）。
4. 触发回滚时必须发布：`ROLLBACK_TRIGGERED` -> `ROLLBACK_COMPLETED`。
5. 任务结束必须发布 `REPORT_GENERATED`，并附带报告索引信息。

### 5.9 README 能力点事件映射（建议）

为保证 README 中“主动感知 + 知识管理 + 桌面协同 + 长期记忆”能力可观测，建议补齐以下事件：

1. 知识库扫描：`KNOWLEDGE_SCAN_STARTED`、`KNOWLEDGE_SCAN_COMPLETED`、`KNOWLEDGE_SCAN_FAILED`
2. 反思与主动输出：`REFLECTION_GENERATED`、`PROACTIVE_MESSAGE_EMITTED`
3. 联网搜索与知识写入：`WEB_SEARCH_CALLED`、`WEB_SEARCH_RESULT`、`KB_UPSERTED`
4. 记忆生命周期：`MEMORY_SUMMARY_COMPACTED`、`MEMORY_PREHEATED`
5. 桌面感知与操作：`DESKTOP_STATE_CHANGED`、`DESKTOP_APP_ACTION`
6. 常驻健康信号：`AGENT_HEARTBEAT`

---

## 6. 可观测指标与告警基线

### 6.1 强制质量目标（来自 agent.md 执行记忆账本验收）

1. 连续多阶段任务中，原始命令引用完整率 = 100%
2. phase 级关键输出被下一阶段读取成功率 >= 99%
3. 审批中断或服务重启后，上下文恢复成功率 >= 99%
4. 偏航触发 replan 事件漏报率 = 0
5. 前端回放事件缺失率 < 1%

### 6.2 运行监控指标（C3/G2 优先）

- `sse_publish_success_rate`
- `sse_delivery_latency_ms_p95/p99`
- `approval_wait_ms_p95`
- `node_retry_rate`
- `phase_failure_rate`
- `replan_trigger_rate`
- `auto_fix_success_rate`
- `rollback_rate`
- `embedding_http_latency_ms_p95/p99`
- `knowledge_scan_success_rate`
- `proactive_emit_success_rate`
- `memory_compaction_latency_ms_p95`
- `desktop_action_success_rate`

### 6.3 告警建议

1. `sse_publish_success_rate` 连续 5 分钟低于阈值时告警。
2. `approval_wait_ms_p95` 超阈值时告警，并关联审批队列长度。
3. `phase_failure_rate` 或 `rollback_rate` 异常抬升时告警。
4. `event_gap_detected`（序列断档）出现即告警，触发快照补偿流程。

---

## 7. 按阶段落地对齐（A~G）

1. A/B 阶段：完成 Tool/Skill 契约、事件枚举与基础 SSE 载荷统一。
2. C 阶段：补齐日志审计、脱敏、监控告警与备份恢复演练。
3. D 阶段：完成 Plan/Phase/Node 全链路事件化、快照与重放、replan 联动。
4. E 阶段：完成 CodeOps 全流程事件发布与报告归档。
5. F 阶段：桌面事件纳入同一事件协议与审计规则。
6. G 阶段：推理服务常驻化后，纳入统一健康检查、延迟与降级监控。

---

## 8. 变更与发布检查清单（PR 必查）

1. 是否新增/修改了 `eventType`、`status`、`errorCode` 枚举。
2. 是否更新了后端契约、前端类型与文档（本文件 + 相关 docs）。
3. 是否补充了成功/失败/超时/审批拒绝四类测试。
4. 是否验证了断线重连、快照补偿、回放一致性。
5. 是否验证了日志字段完整性与脱敏规则。
6. 是否验证了高风险动作审批链路全流程可追踪。

---

## 9. 典型事件流（参考）

### 9.1 正常执行链路

`PLAN_CREATED -> BLUEPRINT_FROZEN -> PLAN_STARTED -> PHASE_STARTED -> NODE_STARTED -> SKILL_CALLED -> SKILL_RESULT -> NODE_COMPLETED -> PHASE_COMPLETED -> PLAN_COMPLETED -> REPORT_GENERATED`

### 9.2 含审批与恢复链路

`NODE_STARTED -> APPROVAL_REQUESTED -> APPROVAL_APPROVED -> EXECUTION_RESUMED -> NODE_COMPLETED`

### 9.3 含失败与重规划链路

`NODE_FAILED -> REPLAN_TRIGGERED -> REPLAN_APPLIED -> PHASE_STARTED -> NODE_RETRYING -> NODE_COMPLETED`

---

## 10. 收口要求

本指南用于确保 Luna 在“陪伴式产品体验”之外具备生产级工程属性：  
**所有功能上线必须同时满足契约稳定、事件可回放、日志可审计、故障可恢复、链路可量化。**
