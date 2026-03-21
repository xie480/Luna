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
10. 当用户给出需求后，先由 **BigModel 一次性完成“全局规划任务”**：产出子任务、串并行关系、所需 skill/tool、风险级别与阶段划分；规划结束后 BigModel 第一阶段任务即完成，后续进入“按阶段执行模式”
11. 新增代码工程能力：支持“编写代码、修改代码、生成并运行测试、根据失败反馈迭代修复”的闭环任务链

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

2. **Master Planner（总规划器，BigModel）**
   - 仅在用户新需求进入时触发一次
   - 输出完整 `PlanBlueprint`：
     - 子任务列表
     - 依赖关系与串并行拓扑
     - 每个子任务所需 Skill/Tool 候选
     - 风险等级与审批策略
     - 阶段划分（Phase 1..N）
   - 规划完成后退出，不参与逐节点执行

3. **Phase Executor（阶段执行器）**
   - 按 `PlanBlueprint` 的阶段顺序执行
   - 阶段内按 DAG 并行/串行调度
   - 阶段结束后进入下一阶段

4. **Model Router（模型路由器）**
   - 按任务类型选择模型：
     - 规划/反思：big
     - 结构化抽取/分类：mid/small
     - 快速迭代/低成本：small/flash
     - 代码/命令生成：专用任务模型（后续可扩展）

5. **Capability Matcher（能力匹配器）**
   - 将每个节点映射到 Skill/Tool（可多候选）
   - 利用 resource embedding + schema 约束 + 历史成功率

6. **Execution Scheduler（执行调度器）**
   - 依据 DAG 进行串并行调度
   - 并行节点进线程池/虚拟线程；串行节点按依赖推进
   - 节点失败触发 loop：诊断 -> 重规划 -> 重试/降级

7. **Observer & Critic（观察与评审）**
   - 收集每步执行结果，判断是否达标
   - 不达标时生成修正子任务（子图插入）

8. **Policy Guard（策略守卫）**
   - 统一复用现有 ExecutionGate + ApprovalService + AuthContext(jti)
   - 审批中断后可恢复到 DAG 当前节点继续执行

9. **Plan Reporter（任务报告器）**
   - 在任务结束时（成功/失败）统一触发报告技能
   - 负责组织任务摘要、节点结果、失败重试明细
   - 产出 HTML 文件并自动打开浏览器

10. **CodeOps Capability Pack（新增，代码工程能力包）**
    - 代码检索、代码生成、补丁应用、测试执行、失败归因、二次修复
    - 支持“改代码 -> 跑测试 -> 修复 -> 再测”的循环编排

---

## 4. 核心流程（两段式：先规划后执行）

### 4.1 规划阶段（BigModel 一次性任务）

定义一次性总规划流程：

1. **Understand**
   - 读取用户输入 + 近期会话 + 长期记忆 + 当前桌面状态
2. **Global Plan**
   - BigModel 生成 `PlanBlueprint`，必须包含：
     - `phases`（阶段划分）
     - `nodes`（子任务）
     - `edges`（依赖）
     - `parallelGroups`（并行组）
     - `resourcePlan`（每节点 skill/tool）
     - `riskPlan`（风险级别与审批需求）
3. **Plan Validate**
   - 结构校验（JSON Schema）
   - 规则校验（无环、无孤立关键节点、风险覆盖完整）
4. **Freeze Plan**
   - 规划冻结入库，标记版本号
   - BigModel 第一个任务结束

> 关键要求：规划阶段结束后，不再依赖 BigModel 逐步“边想边执行”，后续改为“按阶段执行 + 必要时局部重规划”。

### 4.2 执行阶段（按阶段推进）

定义阶段执行循环：

1. **Load Phase**
   - 读取当前阶段节点
2. **Execute**
   - 阶段内按 DAG 调度（并行/串行）
3. **Observe**
   - 聚合节点输出、错误、外部反馈
4. **Critique**
   - 判断本阶段是否完成
5. **Phase Close**
   - 完成则推进下一阶段
   - 失败则执行局部修复（不立即推翻全局规划）
6. **Finalize**
   - 全部阶段完成后输出最终结果
7. **Report**
   - 无论成功/失败，生成并展示 HTML 报告

当满足以下任一条件终止执行：
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
- planningModel（如 big）
- planVersion

### 5.2 PlanBlueprint（新增）
- planId
- phases（阶段定义）
- nodes
- edges
- riskMatrix
- generatedAt
- generatedByModel

### 5.3 PlanPhase（新增）
- phaseId
- planId
- name
- objective
- nodeIds
- phaseOrder
- entryCriteria
- exitCriteria
- status（PENDING/RUNNING/SUCCESS/FAILED）

### 5.4 TaskNode
- nodeId
- planId
- phaseId
- name
- type（ANALYZE / TOOL / SKILL / VALIDATE / SUMMARIZE / REPORT / CODE）
- input
- expectedOutputSchema
- dependencies（前置节点列表）
- parallelGroup（并行组标识）
- status（PENDING/RUNNING/SUCCESS/FAILED/BLOCKED/APPROVAL_PENDING/SKIPPED）
- retryPolicy（maxRetry/backoff）
- retryCount（实际已重试次数）
- modelHint（small/mid/big/flash）
- resourceHint（候选 tool/skill）
- output（节点输出结果，脱敏后）
- outputForNext（传给后续节点的关键字段）
- failReason（失败原因）
- riskLevel（LOW/MEDIUM/HIGH）

### 5.5 TaskEdge
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
- 阶段边界强约束：`Phase N` 全部满足 `exitCriteria` 才能进入 `Phase N+1`

---

## 7. 模型路由策略（Multi-Model）

### 7.1 基础路由表（建议初版）
- 全局规划（只执行一次）：**big**
- 意图识别、分类：small/mid
- 参数补全、schema 修复：mid
- 执行后总结：mid/big
- 安全检测：small（低温）
- 报告文案整理：mid（固定结构，低成本）
- 代码改动方案生成：big/mid（按复杂度）
- 测试失败归因与修复建议：mid

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
- 超过阈值触发局部重规划（优先在当前阶段内修复）

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

> 注意：loop 默认是“执行期局部 loop”，不覆盖“规划期一次性 bigmodel 任务”原则。

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
- 最终仍回到当前阶段继续推进

---

## 11. 数据存储设计建议（可分阶段）

### 11.1 Redis（运行态）
- `luna:plan:{planId}:state`（当前状态）
- `luna:plan:{planId}:readyQueue`
- `luna:plan:{planId}:node:{nodeId}`（节点快照）
- `luna:plan:{planId}:phase:{phaseId}`（阶段快照）
- 快速恢复、短期缓存

### 11.2 PostgreSQL（持久态）
新增表建议：
- `plan_instance`
- `plan_phase`（新增）
- `plan_node`
- `plan_edge`
- `plan_event_log`
- `plan_checkpoint`
- `plan_report`（可存报告文件路径与摘要）
- `plan_blueprint`（新增，保存 bigmodel 全局规划原文）

用途：
- 审计追踪
- 失败复盘
- 离线优化模型路由与资源选择
- 任务报告留档与回放
- 规划结果版本化管理

---

## 12. 前端任务流程图与执行明细展示需求

前端必须支持“任务级可视化”：

1. **流程图展示**
   - 按 DAG 渲染节点与依赖边
   - 区分串行链路与并行分支（并行组颜色区分）
   - 支持阶段视图（按 phase 分组）

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

6. **代码任务节点展示（新增）**
   - 改动文件列表
   - patch 摘要
   - 测试执行结果（通过/失败）
   - 失败测试明细与修复轮次

---

## 13. SSE 与前端交互扩展

建议新增事件：
- `PLAN_CREATED`
- `PLAN_PHASE_STARTED`
- `PLAN_PHASE_FINISHED`
- `PLAN_NODE_RUNNING`
- `PLAN_NODE_SUCCESS`
- `PLAN_NODE_FAILED`
- `PLAN_REPLANNED`
- `PLAN_FINISHED`
- `PLAN_REPORT_READY`（报告 HTML 可访问）
- `PLAN_CODE_PATCH_READY`（新增，代码改动可视化）
- `PLAN_TEST_RESULT`（新增，测试结果推送）

前端可视化：
- 展示 DAG 节点执行进度
- 展示当前并行组
- 审批节点高亮弹窗
- 支持用户中断任务
- 支持从 `PLAN_REPORT_READY` 一键查看任务报告

### 13.1 事件载荷建议（最小字段）

- `PLAN_NODE_SUCCESS`：
  - `planId`
  - `phaseId`
  - `nodeId`
  - `status`
  - `costMs`
  - `output`
  - `outputForNext`

- `PLAN_NODE_FAILED`：
  - `planId`
  - `phaseId`
  - `nodeId`
  - `status`
  - `retryCount`
  - `maxRetry`
  - `failReason`
  - `lastErrorStackBrief`

- `PLAN_TEST_RESULT`（新增）：
  - `planId`
  - `phaseId`
  - `nodeId`
  - `passed`
  - `totalTests`
  - `failedTests`
  - `reportPath`

---

## 14. 任务报告技能（成功/失败必调）

### 14.1 目标
无论 Plan 最终是成功、失败或部分完成，都必须调用一个统一 Skill 生成任务报告：

建议技能名：`generate_task_report_and_open`

### 14.2 输入建议
- `planId`
- `sessionId`
- `userGoal`
- `finalStatus`（SUCCESS/FAILED/PARTIAL/CANCELLED）
- `phases`（阶段执行明细）
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
- 阶段总览（每阶段目标、状态、耗时）
- DAG 流程图（可静态 SVG 或前端渲染）
- 节点执行列表
  - 成功：结果、输出给下游的数据
  - 失败：失败原因、重试次数、最终错误
- 代码改动与测试结果（新增）
- 审批记录
- 结论与下一步建议

---

## 15. 监控与可观测性

关键指标：
- 计划成功率
- 平均阶段数
- 平均 loop 次数
- 节点失败率（按 tool/skill 维度）
- 审批通过率、审批耗时
- 模型路由命中率与成本
- 报告生成成功率
- 测试通过率（新增）
- 自动修复回合数（新增）

日志建议：
- 每个 node 记录 `input/output/error/model/resource/costMs/retryCount/outputForNext`
- 每个 phase 记录 `entry/exit/blockedReason`
- 与现有 `luna_log` 打通 traceId，串联完整链路
- 记录报告生成与浏览器唤起结果
- 记录代码 patch 哈希、测试报告路径（新增）

---

## 16. 分阶段实施路线图

### Phase A：最小可用编排（MVP）
- BigModel 一次性全局规划
- 按阶段执行（无复杂 replanning）
- 支持 DAG 串并行
- 支持审批中断恢复
- 支持 2-3 类模型路由
- 前端最小流程图展示（静态节点状态）

### Phase B：复杂任务 loop
- 节点失败后自动修复与重试
- 支持局部 replanning（子图插入）
- 增强可观测事件
- 前端展示重试次数与失败原因

### Phase C：桌面调度增强
- 引入桌面状态感知节点
- 引入资源互斥与执行窗口管理
- 引入用户可视化任务图与人工介入点
- 接入报告技能并自动打开浏览器

### Phase D：代码工程闭环（新增）
- 支持代码编写/修改/测试子任务
- 支持 patch 预览与审批
- 支持失败测试自动修复循环
- 产出代码变更与测试汇总报告

### Phase E：策略学习与优化
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
- `MasterPlanningService`（BigModel 总规划）
- `PhaseExecutionService`
- `TaskGraphService`
- `ModelRoutingService`
- `CapabilityMatchService`
- `PlanStateStore`（Redis + DB）
- `PlanExecutionController`（对前端暴露任务状态查询）
- `TaskReportSkill`（生成 HTML + 打开浏览器）
- `CodeTaskService`（新增，代码任务编排）
- `TestExecutionService`（新增，测试执行与结果结构化）

---

## 18. 风险与应对

1. 任务图失控（无限 replanning）
- 设最大 loop、最大节点数、最大执行时长

2. 并行执行冲突
- 引入资源锁（窗口/应用级 mutex）

3. 模型成本上升
- 规划只做一次 bigmodel
- 执行阶段优先 mid/small
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

7. 代码任务存在误改风险（新增）
- patch 必须可回滚
- 默认先跑测试后再标记成功
- 高风险改动（删除核心文件/依赖升级）走审批

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
- BigModel 规划任务执行后可正确冻结阶段方案，后续按阶段执行无偏移
- 代码任务中自动测试通过率持续提升，且失败可定位可回滚

---

## 20. 总结

本方案将 Luna 从“单轮对话 + 工具调用”升级为“任务级自治编排系统”：
- 能理解目标
- 能一次性完成全局规划
- 能按阶段稳定执行
- 能在执行中学习与修正
- 能在安全可控下进行桌面级任务推进
- 能将全过程可视化给前端，并在任务结束自动产出可阅读的 HTML 成果报告
- 能执行代码工程闭环任务（编写/修改/测试/修复）

建议先落地 MVP（Phase A），以最小闭环验证架构，再逐步迭代到完整 OpenClaw 风格自治调度。

---

## 21. 本文档对应需要新增的 Skill 与 Tool 清单（最终汇总）

### 21.1 需要新增的 Skill（编排层）

1. `plan_user_requirement_bigmodel`
   - 用途：BigModel 一次性全局规划
   - 输入：userGoal / context / constraints
   - 输出：PlanBlueprint（含阶段、DAG、资源、风险）

2. `validate_plan_blueprint`
   - 用途：校验规划结构合法性（无环、依赖完整、风险覆盖）
   - 输出：validateResult + 修复建议

3. `execute_plan_phase`
   - 用途：执行单个阶段（阶段内调度节点）
   - 输出：phaseResult

4. `replan_failed_nodes`
   - 用途：阶段内局部重规划（失败节点替换/补节点）
   - 输出：patchGraph

5. `aggregate_phase_outputs`
   - 用途：聚合阶段产出，生成下阶段输入上下文
   - 输出：phaseContextForNext

6. `generate_task_report_and_open`
   - 用途：生成 HTML 报告并自动打开浏览器（成功/失败都必须执行）
   - 输出：reportPath / reportUrl / openResult

7. `publish_plan_sse_events`
   - 用途：统一推送 PLAN/PHASE/NODE 事件给前端
   - 输出：eventAck

8. `checkpoint_plan_state`
   - 用途：关键节点落盘与恢复点保存
   - 输出：checkpointId

9. `plan_code_change_tasks`（新增）
   - 用途：把“代码需求”拆成代码改动 + 测试 + 验证节点
   - 输出：CodeTaskBlueprint

10. `generate_code_patch`（新增）
    - 用途：根据目标文件与需求生成可应用补丁
    - 输出：patch + 变更说明

11. `generate_test_cases`（新增）
    - 用途：生成单元测试/集成测试样例
    - 输出：testFiles + 覆盖说明

12. `analyze_test_failure_and_fix`（新增）
    - 用途：读取失败测试与日志，生成修复补丁
    - 输出：fixPatch + rootCause

13. `summarize_code_changes_for_report`（新增）
    - 用途：整理本次代码改动摘要写入任务报告
    - 输出：changeSummary

### 21.2 需要新增的 Tool（执行层）

1. `save_plan_blueprint`
   - 将规划结果写入数据库/Redis（版本化）

2. `load_plan_blueprint`
   - 读取指定 planId 的规划结构

3. `list_phase_nodes`
   - 查询某阶段的全部节点及状态

4. `update_node_status`
   - 更新节点状态/耗时/失败原因/重试次数

5. `append_node_output`
   - 写入节点 output 与 outputForNext

6. `acquire_execution_lock`
   - 并行执行资源锁（窗口/应用/文件级）

7. `release_execution_lock`
   - 释放执行锁

8. `open_browser_with_file`
   - 打开指定 HTML 报告文件

9. `write_html_report_file`
   - 将报告内容写入本地文件系统

10. `query_plan_progress`
    - 前端轮询/补偿查询当前计划进度

11. `emit_plan_event_sse`
    - 底层 SSE 事件发送工具（PLAN_NODE_SUCCESS 等）

12. `record_plan_audit_log`
    - 将计划级事件写入审计日志（便于复盘）

13. `read_repo_tree`（新增）
    - 读取仓库目录结构，供代码任务规划

14. `read_source_file`（新增）
    - 读取源码文件内容

15. `write_source_file`（新增）
    - 写入源码文件（支持备份）

16. `apply_unified_patch`（新增）
    - 应用 patch 到工作区

17. `run_build_command`（新增）
    - 执行编译命令（mvn/gradle）

18. `run_test_command`（新增）
    - 执行测试命令并采集结果

19. `run_lint_command`（新增）
    - 执行静态检查（lint/spotbugs/checkstyle）

20. `run_format_command`（新增）
    - 执行格式化工具

21. `collect_test_report`（新增）
    - 聚合 surefire/junit 报告为结构化 JSON

22. `git_create_checkpoint`（新增）
    - 创建本地回滚点（commit/stash）

23. `git_rollback_checkpoint`（新增）
    - 回滚到指定检查点

24. `search_symbol_references`（新增）
    - 按符号查找引用，辅助重构安全性

25. `scan_dependency_vulnerabilities`（新增）
    - 扫描依赖安全风险（SCA）

26. `capture_desktop_screenshot`（新增，桌面任务增强）
    - 捕获屏幕快照用于状态判断

27. `detect_ui_elements`（新增，桌面任务增强）
    - UI 元素识别（OCR/控件树）

> 备注：以上 Skill/Tool 为建议最小集合，可按 Phase A/B/C/D 分批落地。
