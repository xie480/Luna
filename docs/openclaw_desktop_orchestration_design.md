# Luna 下一阶段技术设计文档：OpenClaw 式桌面调度与多模型任务编排

## 1. 背景与目标

当前 Luna 已具备：
- 对话能力（含 RAG、记忆、工具调用）
- MCP Tool/Skill 资源管理与执行
- 审批链路（敏感操作拦截、SSE 交互、审批后续跑）
- 异步任务执行与日志审计

下一阶段目标：引入 **OpenClaw 式桌面调度能力**，使 Luna 可以：

1. 根据用户输入自主规划任务（Plan）
2. 将复杂任务拆分为多个子任务（Decompose）
3. 判断哪些子任务并行、哪些串行（DAG + Scheduler）
4. 为每个子任务动态选择合适 Skill/Tool（Act）
5. 对复杂任务进行逐步 loop（Observe -> Replan -> Act）
6. 灵活切换不同模型（Router：small/mid/big/flash + 任务型模型）
7. 在执行全过程保留审批、安全、可审计、可恢复能力
8. 前端可视化展示任务流程图与子任务执行进度
9. 无论任务最终成功或失败，都由 Luna 调用技能生成 HTML 任务报告并自动唤起浏览器展示

---

## 2. 设计原则

### 2.1 核心原则
- **可解释**：每个步骤有“计划依据、模型选择依据、工具选择依据”
- **可控**：安全门、审批门必须在任务图执行阶段持续生效
- **可恢复**：中断后可从任务图中间节点恢复
- **可观测**：每个子任务的状态、耗时、输入输出、失败原因可追踪
- **渐进式实现**：先单机、先文字，再扩展桌面自动化高风险动作

### 2.2 非目标（本阶段）
- 不追求完全自治（仍保留审批与策略约束）
- 不直接开放任意系统级危险操作（如无审批执行 shell/注册表）
- 不引入重型工作流引擎（先用现有 RocketMQ + DB + Redis 组合）

---

## 3. 总体架构

建议新增「编排中枢」模块（逻辑可先放 service 层）：

1. **Intent Analyzer（意图分析）**
   - 输入：用户请求 + 历史上下文 + 桌面状态
   - 输出：任务目标、约束、成功标准

2. **Planner（规划器）**
   - 把目标拆成 Task DAG（有向无环图）
   - 定义每个节点的依赖、可并行性、重试策略、所需能力标签

3. **Model Router（模型路由器）**
   - 按任务类型选择模型：
     - 规划/反思：big
     - 结构化抽取/分类：mid/small
     - 快速迭代/低成本：small/flash
     - 代码/命令生成：专用任务模型（后续可扩展）

4. **Capability Matcher（能力匹配器）**
   - 将每个节点映射到 Skill/Tool（可多候选）
   - 利用 resource embedding + schema 约束 + 历史成功率

5. **Execution Scheduler（执行调度器）**
   - 依据 DAG 进行串并行调度
   - 并行节点进线程池/虚拟线程；串行节点按依赖推进
   - 节点失败触发 loop：诊断 -> 重规划 -> 重试/降级

6. **Observer & Critic（观察与评审）**
   - 收集每步执行结果，判断是否达标
   - 不达标时生成修正子任务（子图插入）

7. **Policy Guard（策略守卫）**
   - 统一复用现有 ExecutionGate + ApprovalService + AuthContext(jti)
   - 审批中断后可恢复到 DAG 当前节点继续执行

8. **Plan Reporter（任务报告器，新增）**
   - 在任务结束时（成功/失败）统一触发报告技能
   - 负责组织任务摘要、节点结果、失败重试明细
   - 产出 HTML 文件并自动打开浏览器

---

## 4. 核心流程（OpenClaw Loop）

定义主循环：

1. **Understand**
   - 读取用户输入 + 近期会话 + 长期记忆 + 当前桌面状态
2. **Plan**
   - 产出初始任务图（DAG）
3. **Execute**
   - 调度节点执行（并行/串行）
4. **Observe**
   - 聚合节点输出、错误、外部反馈
5. **Critique**
   - 判断当前目标是否完成，是否偏航
6. **Replan**
   - 如未完成，补充/替换子任务，进入下一轮 loop
7. **Finalize**
   - 汇总结果回复用户，落盘审计，更新记忆
8. **Report（新增）**
   - 无论成功/失败，调用“任务报告技能”生成 HTML 并自动打开

当满足以下任一条件终止 loop：
- 达成成功标准
- 达到最大迭代次数
- 出现不可恢复错误
- 用户主动取消

---

## 5. 任务图模型（Task DAG）

建议引入以下核心对象（后续可落表）：

### 5.1 PlanContext
- planId
- sessionId（使用 JWT jti）
- userGoal
- constraints（时间/权限/隐私）
- successCriteria
- currentLoopIndex

### 5.2 TaskNode
- nodeId
- planId
- name
- type（ANALYZE / TOOL / SKILL / VALIDATE / SUMMARIZE / REPORT）
- input
- expectedOutputSchema
- dependencies（前置节点列表）
- parallelGroup（并行组标识）
- status（PENDING/RUNNING/SUCCESS/FAILED/BLOCKED/APPROVAL_PENDING/SKIPPED）
- retryPolicy（maxRetry/backoff）
- retryCount（实际已重试次数，新增）
- modelHint（small/mid/big/flash）
- resourceHint（候选 tool/skill）
- output（节点输出结果，脱敏后）
- outputForNext（传给后续节点的关键字段，新增）
- failReason（失败原因，新增）

### 5.3 TaskEdge
- fromNodeId
- toNodeId
- condition（可选：条件分支）

---

## 6. 并行与串行调度策略

### 6.1 串行条件
- 数据依赖：后节点输入依赖前节点输出
- 风险依赖：高敏感动作必须在确认节点后执行
- 资源冲突：同一桌面对象/窗口不能并行操作

### 6.2 并行条件
- 无数据依赖
- 无资源互斥
- 节点执行时间长且可独立（如多源检索、并行抓取）

### 6.3 调度规则（建议）
- 每轮调度取所有 `dependencies` 已满足且 `status=PENDING` 的节点
- 按优先级分层：
  1) 验证节点
  2) 核心执行节点
  3) 可选增强节点
- 同层并行，层间串行

---

## 7. 模型路由策略（Multi-Model）

### 7.1 基础路由表（建议初版）
- 意图识别、分类：small/mid
- 任务拆解与重规划：big
- 参数补全、schema 修复：mid
- 执行后总结：mid/big
- 安全检测：small（低温）
- 报告文案整理：mid（固定结构，低成本）

### 7.2 路由输入信号
- 任务复杂度（节点数、依赖深度）
- 风险等级（敏感度、需审批）
- 时延预算（实时 vs 后台）
- 成本预算（token/调用次数）
- 历史效果（同类任务成功率）

### 7.3 路由回退策略
- 首选模型失败 -> 退到同类备用模型
- 连续失败 -> 简化目标、减少步骤、请求用户确认

---

## 8. Tool/Skill 选择策略

### 8.1 选择依据
- 语义相似度（query vs resource embedding）
- inputSchema 匹配度
- 历史成功率
- 执行时延与稳定性
- sensitivity/requiresApproval

### 8.2 选择流程
1. 初筛候选（topK）
2. 规则过滤（schema、权限、审批）
3. rerank（模型 + 规则混合打分）
4. 产出主候选 + 备用候选

### 8.3 执行失败切换
- 主候选失败时自动尝试备用候选
- 超过阈值触发 replanning

---

## 9. 复杂任务 Loop 机制

针对复杂任务，每一步执行后都做 mini-loop：

1. 节点执行
2. 节点级校验（输出 schema + 语义校验）
3. 若失败：
   - 参数修复（TOOL_ARGS_REPAIR_PROMPT）
   - 重新选择资源
   - 或插入前置探测节点
4. 记录节点复盘信息（用于后续学习）

---

## 10. 安全与审批集成

### 10.1 安全链路
- 所有执行节点统一走 ReflectionToolExecutor / SkillExecutor
- ExecutionGate 继续负责高敏感识别
- 审批逻辑不下沉到 Planner，保持单一职责

### 10.2 审批中断恢复
- 当节点触发 `NeedApprovalException`：
  - 节点状态置为 `APPROVAL_PENDING`
  - Plan 状态置为 `WAITING_USER_APPROVAL`
  - 通过 SSE 发 `APPROVAL_REQUEST`
- 审批回调后：
  - 同意：节点继续执行
  - 拒绝：节点标记 `SKIPPED`，由 Planner 决定是否补替代节点
- 最终仍回到主 loop 继续

---

## 11. 数据存储设计建议（可分阶段）

### 11.1 Redis（运行态）
- `luna:plan:{planId}:state`（当前状态）
- `luna:plan:{planId}:readyQueue`
- `luna:plan:{planId}:node:{nodeId}`（节点快照）
- 快速恢复、短期缓存

### 11.2 PostgreSQL（持久态）
新增表建议：
- `plan_instance`
- `plan_node`
- `plan_edge`
- `plan_event_log`
- `plan_checkpoint`
- `plan_report`（新增，可存报告文件路径与摘要）

用途：
- 审计追踪
- 失败复盘
- 离线优化模型路由与资源选择
- 任务报告留档与回放

---

## 12. 前端任务流程图与执行明细展示需求（新增）

前端必须支持“任务级可视化”：

1. **流程图展示**
   - 按 DAG 渲染节点与依赖边
   - 区分串行链路与并行分支（并行组颜色区分）

2. **每个子任务实时进度**
   - 状态：PENDING / RUNNING / SUCCESS / FAILED / APPROVAL_PENDING / SKIPPED
   - 开始时间、结束时间、耗时
   - 当前重试次数与最大重试次数

3. **成功节点展示**
   - 执行结果（output）
   - 传给下一任务的关键数据（outputForNext）
   - 下游依赖节点列表

4. **失败节点展示**
   - 失败原因（failReason）
   - 重试次数（retryCount）
   - 每次重试失败摘要（可折叠）

5. **审批节点展示**
   - 审批状态与 taskId
   - 同意/拒绝操作入口
   - 审批后节点恢复执行轨迹

---

## 13. SSE 与前端交互扩展

建议新增事件：
- `PLAN_CREATED`
- `PLAN_NODE_RUNNING`
- `PLAN_NODE_SUCCESS`
- `PLAN_NODE_FAILED`
- `PLAN_REPLANNED`
- `PLAN_FINISHED`
- `PLAN_REPORT_READY`（新增，报告 HTML 可访问）

前端可视化：
- 展示 DAG 节点执行进度
- 展示当前并行组
- 审批节点高亮弹窗
- 支持用户中断任务
- 支持从 `PLAN_REPORT_READY` 一键查看任务报告

### 13.1 事件载荷建议（最小字段）

- `PLAN_NODE_SUCCESS`：
  - `planId`
  - `nodeId`
  - `status`
  - `costMs`
  - `output`
  - `outputForNext`

- `PLAN_NODE_FAILED`：
  - `planId`
  - `nodeId`
  - `status`
  - `retryCount`
  - `maxRetry`
  - `failReason`
  - `lastErrorStackBrief`

---

## 14. 任务报告技能（成功/失败必调，新增）

### 14.1 目标
无论 Plan 最终是成功、失败或部分完成，都必须调用一个统一 Skill 生成任务报告：

建议技能名：`generate_task_report_and_open`

### 14.2 输入建议
- `planId`
- `sessionId`
- `userGoal`
- `finalStatus`（SUCCESS/FAILED/PARTIAL/CANCELLED）
- `nodes`（节点执行明细）
- `timeline`（关键时间线）
- `summary`（模型总结）

### 14.3 技能执行行为（强制）
1. 生成结构化 HTML 报告（含流程图、节点状态、成功产出、失败原因、重试记录）
2. 将 HTML 写入本地文件（建议目录：`./data/reports/{planId}.html`）
3. 自动唤起系统默认浏览器打开该 HTML
4. 返回：
   - `reportPath`
   - `reportUrl`（file://...）
   - `openResult`

### 14.4 报告页面建议区块
- 任务概览（目标、状态、总耗时）
- DAG 流程图（可静态 SVG 或前端渲染）
- 节点执行列表
  - 成功：结果、输出给下游的数据
  - 失败：失败原因、重试次数、最终错误
- 审批记录
- 结论与下一步建议

---

## 15. 监控与可观测性

关键指标：
- 计划成功率
- 平均 loop 次数
- 节点失败率（按 tool/skill 维度）
- 审批通过率、审批耗时
- 模型路由命中率与成本
- 报告生成成功率（新增）

日志建议：
- 每个 node 记录 `input/output/error/model/resource/costMs/retryCount/outputForNext`
- 与现有 `luna_log` 打通 traceId，串联完整链路
- 记录报告生成与浏览器唤起结果

---

## 16. 分阶段实施路线图

### Phase A：最小可用编排（MVP）
- 单轮 Plan（无 Replan）
- 支持 DAG 串并行
- 支持审批中断恢复
- 支持 2-3 类模型路由
- 前端最小流程图展示（静态节点状态）

### Phase B：复杂任务 loop
- 节点失败后自动修复与重试
- 支持 replanning（子图插入）
- 增强可观测事件
- 前端展示重试次数与失败原因

### Phase C：桌面调度增强
- 引入桌面状态感知节点
- 引入资源互斥与执行窗口管理
- 引入用户可视化任务图与人工介入点
- 接入报告技能并自动打开浏览器

### Phase D：策略学习与优化
- 基于历史成功率优化路由和工具选择
- 自动调整并行度与重试策略
- 引入任务模板库（常见任务一键规划）
- 报告模板与复盘建议智能化

---

## 17. 与现有代码的映射建议

可复用：
- AgentService（可作为 Planner/Executor 的上层入口）
- ReflectionToolExecutor / SkillExecutor
- ApprovalService / NeedApprovalException / SSE
- LlmClientUtil（模型调用与 rerank）
- SessionService + RAG + PromptAssembler
- RocketMQ 异步链路

建议新增：
- `PlanOrchestratorService`
- `TaskGraphService`
- `ModelRoutingService`
- `CapabilityMatchService`
- `PlanStateStore`（Redis + DB）
- `PlanExecutionController`（对前端暴露任务状态查询）
- `TaskReportSkill`（生成 HTML + 打开浏览器）

---

## 18. 风险与应对

1. 任务图失控（无限 replanning）
- 设最大 loop、最大节点数、最大执行时长

2. 并行执行冲突
- 引入资源锁（窗口/应用级 mutex）

3. 模型成本上升
- 路由降级 + 缓存中间结果 + 批量执行

4. 审批频繁打断体验
- 合并审批（同批高风险节点打包）
- 预审批策略（可配置白名单）

5. 可解释性不足
- 每次计划都保留“规划理由 + 选择理由”
- 强制记录 `outputForNext` 与 `failReason`

6. 报告技能失败导致无法展示成果
- 报告技能设兜底：至少输出 JSON 报告并保存在日志
- 浏览器唤起失败时返回可点击文件路径

---

## 19. 验收标准（建议）

- 复杂任务（>=6 节点）可稳定执行
- 并行/串行逻辑正确率 >= 95%
- 审批中断后恢复成功率 >= 98%
- 失败可恢复率 >= 90%
- 全链路日志可追溯（planId + traceId）
- 前端可实时看到节点级状态变化
- 前端可查看每个子任务：
  - 成功结果
  - 传递给下游的数据
  - 失败原因与重试次数
- 无论任务成功/失败，均生成 HTML 报告并自动打开浏览器，成功率 >= 99%

---

## 20. 总结

本方案将 Luna 从“单轮对话 + 工具调用”升级为“任务级自治编排系统”：
- 能理解目标
- 能分解与调度
- 能在执行中学习与修正
- 能在安全可控下进行桌面级任务推进
- 能将全过程可视化给前端，并在任务结束自动产出可阅读的 HTML 成果报告

建议先落地 MVP（Phase A），以最小闭环验证架构，再逐步迭代到完整 OpenClaw 风格自治调度。
