# PlanOrchestratorController 主链路（OpenClaw 任务图规划执行）

> 本文档效仿 `docs/ChatController/chat-flow-v2.md`，以自然语言描述 **PlanOrchestratorController** 与其核心实现 **PlanOrchestratorServiceImpl** 的完整主链路。文中仅保留从 HTTP 接口入口、计划创建、阶段调度到最终报告生成的核心步骤，省去非关键的辅助方法。

---

## 1. HTTP 入口 – 接收用户的计划请求

### `POST /luna/api/plan/run`
- **实现位置**：[`PlanOrchestratorController.run()`](src/main/java/org/yilena/luna/controller/PlanOrchestratorController.java:79)
- **核心职责**：
  1. **检查**请求体中的 `userGoal` 是否为空；
  2. **解析会话标识**：优先使用 JWT 中的 `jti`，若不存在则使用请求体的 `sessionId`，两者皆缺时回退到 `SessionConstant.PLAN_DEFAULT_SESSION_ID`；
  3. **调用业务层** `planOrchestratorService.createAndRunPlan(sessionId, userGoal.trim())`，开启计划的完整编排流程；
  4. **包装返回**：尝试把业务层返回的字符串解析为结构化 JSON，若解析失败则直接返回原始文本。

> 这一步把外部的 HTTP 请求统一映射为业务入口，并提供最基础的防护与上下文准备。

---

## 2. 服务层入口 – `createAndRunPlan`

### `PlanOrchestratorServiceImpl.createAndRunPlan`
- **实现位置**：[`PlanOrchestratorServiceImpl.createAndRunPlan()`](src/main/java/org/yilena/luna/service/impl/PlanOrchestratorServiceImpl.java:99)
- **主流程概览**（自然语言描述）：
  1. **校验参数**：确认 `sessionId` 与 `userGoal` 均非空。
  2. **蓝图前置编排**：调用 `taskOrchestratorService().orchestrateBlueprintInput(sessionId, userGoal)`，得到 `BlueprintOrchestrationResult`（包括决策、输入重构、上下文包、节点工作集以及蓝图草稿）。
  3. **确定最终目标**：通过 `resolveEffectiveGoal` 合并重构结果与草稿，得到 `effectiveGoal`（若仍为空则报错）。
  4. **创建计划实例**：使用 `"plan-" + SnowflakeIdUtil.nextIdStr()` 生成唯一 `planId`，构造 `PlanInstance`（状态 `PENDING`），写入数据库。
  5. **推送创建事件**：使用 `emitPlanEvent` 与 `emitFrontProgress` 向 SSE 与审计系统报告 `PLAN_CREATED`，前端即可看到计划已创建。
  6. **全局蓝图生成**：调用 `masterPlanningService.generateBlueprint(...)`，获得 OpenClaw 的 **PlanBlueprint**（任务图抽象）。
  7. **蓝图校验**：通过 `blueprintValidationService.validate` 检查结构合法性；若失败则把计划标记为 `FAILED` 并返回错误信息。
  8. **蓝图持久化**：使用 `planBlueprintTools.savePlanBlueprint` 将蓝图写入持久化存储，确保后续阶段能够查询完整图结构。
  9. **物化阶段和节点**：解析蓝图中的 `phases` 与 `nodes`，转化为 `PlanPhase`、`PlanNode` 实体并写入数据库（内部调用 `materializePhasesAndNodes`）。
 10. **构建图边**：解析蓝图的 `edges` 并创建 `PlanEdge`，完成任务拓扑的落库（内部调用 `buildEdgesFromBlueprint`）。
 11. **进入运行状态**：将 `PlanInstance` 状态改为 `RUNNING`，并推送 `PLAN_RUNNING` 事件。
 12. **写入蓝图轮次审计**：把蓝图生成阶段的快照写入 `RoundState`（`persistBlueprintRoundState`），为后续阶段提供审计溯源。
 13. **顺序执行各阶段**（核心循环）：
      - 按 `phaseOrder` 读取所有 `PlanPhase`；
      - 对每个阶段调用 **PhaseExecutionService** 的 `executePhase(planId, phase, sessionId)`；
      - 根据返回的成功或错误，分别发送 `PLAN_PHASE_STARTED` / `PLAN_PHASE_FINISHED` 事件；
      - 若出现错误或待审批节点，立即中止后续阶段的执行。
 14. **收尾报告**：所有阶段结束后（无论成功还是中途失败），调用 `finalizeAndReport(planId)` 生成完整的 HTML 报告并写入磁盘。
 15. **合并结果并回写**：把阶段执行结果与报告信息合并为统一的 JSON；如果 `callbackToChat` 为 `true`，通过 `sendFinalResultToLuna` 把报告包装为 Prompt 并交给 `ChatService.chat`，实现计划结果在对话流中的展示。
 16. **返回最终 JSON**：记录执行完毕日志并将整体结果返回给 Controller 层。

> 该入口把用户的高层目标抽象为 **PlanBlueprint**，再逐层落地为 **阶段 → 节点 → 工具**，并在每个关键节点通过统一的事件体系实现可观测、可审计的状态推送。

---

## 3. 阶段执行主链路 – `executePhase`

### `PhaseExecutionServiceImpl.executePhase`
- **实现位置**：[`PhaseExecutionServiceImpl.executePhase()`](src/main/java/org/yilena/luna/service/impl/PhaseExecutionServiceImpl.java:86)
- **关键步骤**（自然语言描述）：
  1. **加载阶段节点**：从数据库读取当前阶段的全部 `PlanNode`。
  2. **拓扑排序**：使用 Kahn 算法（`resolveExecutionBatches`）把节点划分为若干批次，确保所有依赖先于子节点执行。
  3. **批次并行执行**：对每个批次使用虚拟线程池并行调用对应的 Tool / Prompt / Resource；批次之间串行，以保证前置节点完成后才启动后续节点。
  4. **节点调度**：依据 `nodeType` 调用相应的工具或工作流，处理可能出现的审批、重试与错误捕获。
  5. **状态与事件上报**：每个节点的成功、失败或待审批都会通过 `PlanEventTools` 推送 SSE 事件；阶段整体进度通过 `emitFrontPhaseProgress` 向前端报告。
  6. **中断策略**：如果某个批次出现关键节点失败，或出现待审批节点，立即中止后续批次并返回错误信息给上层。
  7. **返回阶段结果**：构造包含成功、失败、待审批计数以及耗时的 JSON，供 `createAndRunPlan` 判断是否继续执行后续阶段。

> 阶段执行是 OpenClaw 调度的核心，引擎通过 DAG 拓扑把抽象任务图转化为真实的工具调用，同时保持全链路可观测。

---

## 4. 报告生成 – `finalizeAndReport`

### `PlanOrchestratorServiceImpl.finalizeAndReport`
- **实现位置**：[`PlanOrchestratorServiceImpl.finalizeAndReport()`](src/main/java/org/yilena/luna/service/impl/PlanOrchestratorServiceImpl.java:364)
- **主要流程**：
  1. 查询 `PlanInstance`、所有 `PlanPhase` 与 `PlanNode`，统计节点成功、失败、跳过数量，计算整体的 `PlanFinalStatus`（`SUCCESS` / `PARTIAL` / `FAILED`）。
  2. 推送前端 `REPORT_GENERATING` 事件，告知用户报告正在生成。
  3. 调用内部 `buildReportHtml` 生成包含计划概览、阶段总览、节点明细的完整 HTML 报告。
  4. 使用 `planReportTools.writeHtmlReportFile` 将报告写入磁盘，并尝试通过 `planReportTools.openBrowserWithFile` 在本地打开（若失败仅记录警告）。
  5. 更新 `PlanInstance` 的 `finalStatus` 与 `finishedAt`，写回数据库并发送 `PLAN_REPORT_READY` 事件。
  6. 返回包含报告路径、访问 URL、节点统计等信息的结构化 JSON。

> 报告是计划执行的最终产出，为前端提供可视化回放与审计依据。

---

## 5. 小结

`PlanOrchestratorController` 与 `PlanOrchestratorServiceImpl` 合力实现了 **OpenClaw** 风格的端到端任务编排：
1️⃣ 接收用户目标 → 生成全局蓝图 → 持久化阶段/节点/边 → 通过 **PhaseExecutionService** 按 DAG 调度执行 → 处理错误与审批 → 最终生成可视化报告。整个过程中，关键节点通过统一的 **事件推送** 与 **审计快照** 保证了可观测、可恢复、可回放。

---

*本文档基于当前代码实现撰写，若代码有变动请同步更新文档。*
