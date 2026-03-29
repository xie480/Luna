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

## 2.1 分层边界（必须）

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

## 2.2 OpenClaw 编排约束（必须）

围绕 Plan/Phase/Node：

1. Plan 创建后需冻结蓝图（Blueprint Freeze）
2. Phase/Node 状态迁移必须合法（如：PENDING -> RUNNING -> SUCCESS/FAILED）
3. 每个失败节点必须记录 retryCount、failReason、errorCode、costMs
4. 支持 replan 时必须保留原计划与补丁计划关系（version/parentPlanId）
5. 关键节点必须可恢复（checkpoint + ledger + snapshot）

## 2.3 CodeOps 闭环约束（必须）

针对读码、改码、测试、修复、报告链路：

1. 补丁应用前必须 dry-run
2. build/test/lint/format 结果必须结构化落库或落日志
3. 自动修复循环必须有最大重试次数与熔断条件
4. 回滚动作必须可追踪（触发人/触发策略/回滚范围）
5. 报告输出必须包含变更摘要、测试结果、风险提示

---

## 3. 编码强制规则（开发执行清单）

## 3.1 契约优先

新增功能前，必须先定义：

- inputSchema
- outputSchema
- errorCode 列表
- eventType 列表
- SSE payload 字段（向后兼容）

## 3.2 常量集中管理

以下内容必须集中在常量或枚举中统一管理：

- EventType（如 PLAN_CREATED、PHASE_STARTED）
- Status（RUNNING/SUCCESS/FAILED/APPROVAL_PENDING 等）
- ErrorCode（超时、参数错误、依赖故障、审批拒绝等）
- Topic/Key/Channel 名称

## 3.3 失败优先设计

每个外部依赖（LLM、DB、Redis、HTTP、子进程、MQ）必须有：

- timeout
- retry（指数退避）
- fallback（降级路径）
- 审计日志（含失败原因）

## 3.4 可恢复优先

中断恢复能力必须覆盖：

- 审批中断恢复
- 服务重启恢复
- 阶段失败后重试恢复
- 局部 replan 后继续执行

---

## 4. 日志与审计规范（新增/完善重点）

## 4.1 日志等级与用途

- **INFO**：关键业务节点进展（开始/完成/发布事件）
- **WARN**：可恢复异常（重试、降级、超时）
- **ERROR**：不可恢复异常（任务中断、数据不一致、状态机非法迁移）
- **AUDIT（逻辑级，可映射 INFO）**：审批、敏感动作、回滚、计划变更等审计行为

## 4.2 结构化日志字段（必须）

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

## 4.3 关键行为必打日志清单

1. Plan 创建、冻结、启动、完成、失败
2. Phase 启动、完成、失败
3. Skill 调用开始/结束（输入输出摘要，不打敏感原文）
4. Tool 实际执行结果（命令类需额外记录白名单命中情况）
5. 审批请求创建、推送、回调、拒绝、超时、恢复执行
6. Replan 触发原因、重规划结果、补丁应用结果
7. 回滚触发、回滚结果
8. SSE 发布成功/失败（失败要记录客户端标识与重试信息）

## 4.4 日志脱敏规范

禁止明文输出：

- token、密钥、密码、cookie
- 用户敏感身份信息
- 本地绝对路径（按需打掩码）
- 完整私密文本内容（只保留摘要或哈希）

---

## 5. SSE 发布规范（关键阶段必须 publish）

## 5.1 统一发布目标

SSE 用于把“可视化执行状态 + 关键消息（msg）”实时推送前端。  
要求做到：

1. 状态单调可理解（前端可驱动状态机）
2. 字段稳定向后兼容
3. 丢事件可通过快照补偿

## 5.2 建议统一 payload 结构

