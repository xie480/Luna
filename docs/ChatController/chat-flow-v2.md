# ChatController.message 主链路

> 对应实现方法为 `ChatController.chat(@RequestBody ChatRequest chatRequest)`，接口路径为 `POST /luna/api/chat/message`。  
> 下文只展开 `message` 主链路，不展开 `startup`、`shutdown`、历史查询等旁路接口。

## 1. Controller 入口层

### 1.1 接收 HTTP 请求
- 当前步骤在做什么：`ChatController.chat` 接收 `ChatRequest`，承接 `/luna/api/chat/message` 的 HTTP POST 请求。
- 为什么要这么做：Controller 只负责协议适配，不负责聊天治理、工具决策和模型编排。
- 输入是什么：HTTP 请求体，反序列化后的 `ChatRequest`。
- 输出是什么：可交给服务层处理的 `ChatRequest`。
- 对下一步有什么影响：成功后调用链进入 `ChatServiceImpl.chat`。

### 1.2 转交服务层
- 当前步骤在做什么：直接调用 `chatService.chat(chatRequest)`。
- 为什么要这么做：把主业务逻辑集中在 Service 层，避免 Controller 变成编排中心。
- 输入是什么：`ChatRequest`。
- 输出是什么：`ResponseEntity<Object>`。
- 对下一步有什么影响：后续所有状态治理、工具调用、主模型生成和持久化都由 `ChatServiceImpl.chat` 接管。

## 2. ChatServiceImpl.chat 总入口

### 2.1 规范化用户输入
- 当前步骤在做什么：通过 `Optional.ofNullable(chatRequest).map(ChatRequest::getUserInput).map(String::trim).orElse("")` 提取并清洗 `userInput`，得到 `input`。
- 为什么要这么做：后续所有流程都依赖规范化输入，先去空值和首尾空白能减少脏分支。
- 输入是什么：`ChatRequest`。
- 输出是什么：规范化后的 `input`。
- 对下一步有什么影响：空值校验和后续治理都以这个 `input` 为准。

### 2.2 拦截空输入
- 当前步骤在做什么：若 `input.isEmpty()`，直接返回 `400 Bad Request`，响应体为 `empty input`。
- 为什么要这么做：空输入没有继续执行上下文治理、工具决策和模型推理的意义。
- 输入是什么：`input`。
- 输出是什么：错误响应或继续向下。
- 对下一步有什么影响：只有非空输入才会进入完整主链路。

### 2.3 发布前端思考状态
- 当前步骤在做什么：调用 `statusPublisher.publish(DEFAULT_CLIENT_ID, STATUS_THINKING, VALUE_THINKING)`。
- 为什么要这么做：前端需要立即知道请求已进入处理中。
- 输入是什么：默认客户端和状态常量。
- 输出是什么：一条状态事件。
- 对下一步有什么影响：后续检索态、整理态、空闲态都围绕这轮处理继续更新。

### 2.4 生成运行时会话 ID
- 当前步骤在做什么：优先读取 `AuthContextHolder.getSessionId()`，为空时用 `yyyy:MM:dd` 格式的当前时间生成 `runtimeSessionId`。
- 为什么要这么做：后续上下文编译、状态写回、挂起缓存、审计、记忆写入都必须落到同一会话。
- 输入是什么：认证上下文中的 sessionId 或当前时间。
- 输出是什么：`runtimeSessionId`。
- 对下一步有什么影响：预工具流水线、挂起分支、正式轮次都统一使用这个会话 ID。

## 3. 预工具状态驱动流水线

### 3.1 发布检索状态
- 当前步骤在做什么：调用 `statusPublisher.publish(DEFAULT_CLIENT_ID, STATUS_RETRIEVING, VALUE_RETRIEVING)`。
- 为什么要这么做：这一阶段将进入上下文治理、检索和节点工作集准备。
- 输入是什么：状态常量。
- 输出是什么：检索状态事件。
- 对下一步有什么影响：前端会显示当前请求正在做上下文准备而非正式回复。

### 3.2 构建 `CHAT_PRE_TOOL` 轮次请求
- 当前步骤在做什么：构建 `RoundPipelineRequest`，关键字段为：`sessionId=runtimeSessionId`、`userInput=input`、`stage=CHAT_PRE_TOOL`、`repairSeed=input`、`runMainModel=false`、`assistantReplyOverride=""`、`preAssemblyTriggerSource=PRE_ASSEMBLY_INPUT`、`postSummaryTriggerSource=CHAT_PRE_TOOL`、`replaceHistoryWithSummary=false`、`writeRoundState=false`。
- 为什么要这么做：这一轮只负责补齐治理工件和节点工作集，不负责正式生成回复。
- 输入是什么：会话 ID、用户输入、预工具阶段常量。
- 输出是什么：预工具 `RoundPipelineRequest`。
- 对下一步有什么影响：状态驱动流水线会按“只治理、不回包”的模式执行。

### 3.3 调用 `stateDrivenContextPipeline.run`
- 当前步骤在做什么：以 `triggerSource=CHAT_PRE_TOOL` 包装预工具轮次请求并调用 `stateDrivenContextPipeline.run(...)`。
- 为什么要这么做：统一复用状态驱动流水线，先补齐请求，再交给轮次编排器。
- 输入是什么：`StateDrivenContextPipelineRequest`。
- 输出是什么：`preToolPipelineResult`。
- 对下一步有什么影响：后续能否继续做工具决策，取决于这里是否返回完整治理产物。

### 3.4 处理预工具阻断
- 当前步骤在做什么：若 `preToolPipelineResult == null` 或 `preToolPipelineResult.isBlocked()`，发布 `IDLE` 并返回 `503`，响应体为 `contextGovernanceBlockedPayload("chat pre-tool pipeline blocked")`。
- 为什么要这么做：预工具阶段都失败了，后续工具决策和正式回复就没有可靠输入。
- 输入是什么：`preToolPipelineResult`。
- 输出是什么：阻断响应或继续执行。
- 对下一步有什么影响：主链路可能在这里提前结束。

### 3.5 校验预工具核心工件
- 当前步骤在做什么：从 `preToolPipelineResult` 提取 `decision`、`contextPackage`、`reconstruction`、`nodeWorkset`，并校验四者是否都存在。
- 为什么要这么做：后续片段抽取、工具决策、正式轮次组装都依赖这四类工件。
- 输入是什么：`preToolPipelineResult`。
- 输出是什么：完整工件，或 `503` 响应。
- 对下一步有什么影响：若任一工件缺失，则返回 `contextGovernanceBlockedPayload("chat pre-tool pipeline artifacts missing")`；否则进入下一阶段。

## 4. StateDrivenContextPipelineImpl.run

### 4.1 校验状态驱动请求
- 当前步骤在做什么：检查 `request` 和 `request.getRoundPipelineRequest()` 是否为空。
- 为什么要这么做：没有轮次请求就无法进入后续水化和编排。
- 输入是什么：`StateDrivenContextPipelineRequest`。
- 输出是什么：阻断结果 `state_driven_context_pipeline_request_missing` 或继续执行。
- 对下一步有什么影响：只有请求合法时才会进入 `hydrateRoundRequest`。

### 4.2 水化轮次请求
- 当前步骤在做什么：调用 `hydrateRoundRequest(request)`，把原始轮次请求补齐成可执行的完整请求。
- 为什么要这么做：上游传进来的请求可能只有基础字段，实际执行前还需要补齐决策、上下文包、重构结果、节点工作集和片段池。
- 输入是什么：原始状态驱动请求。
- 输出是什么：`hydratedRoundRequest` 或 `null`。
- 对下一步有什么影响：若水化失败，直接返回 `state_driven_context_pipeline_hydration_failed`。

### 4.3 构建审计追踪上下文
- 当前步骤在做什么：从水化请求中提取 `sessionId`、`triggerSource`、`planId`、`nodeId`，生成 `traceId`。
- 为什么要这么做：后续多个钩子和轮次执行结果都要共享同一条链路追踪信息。
- 输入是什么：原始请求和水化后的请求。
- 输出是什么：追踪上下文。
- 对下一步有什么影响：所有审计记录和状态迁移日志都会复用这套上下文。

### 4.4 记录执行前钩子

#### 4.4.1 `reconstruct` 钩子
- 当前步骤在做什么：记录是否已有 `reconstructionResult` 和当前 `stage`。
- 为什么要这么做：便于确认输入重构工件是否已就绪。
- 输入是什么：`hydratedRoundRequest`。
- 输出是什么：一条审计记录和一条状态迁移日志。
- 对下一步有什么影响：后续可以追查阻断是否发生在重构阶段。

#### 4.4.2 `recall` 钩子
- 当前步骤在做什么：记录是否已有 `nodeWorksetResult`、`executionCandidates`、`mcpResourceHints`。
- 为什么要这么做：工具决策和正式轮次都依赖这些工作集结果。
- 输入是什么：`hydratedRoundRequest`。
- 输出是什么：召回阶段审计。
- 对下一步有什么影响：为后续排查“工作集未就绪”提供证据。

#### 4.4.3 `rerank` 钩子
- 当前步骤在做什么：记录 `nodeWorksetResult.getRerankResult()` 是否存在。
- 为什么要这么做：重排结果决定知识证据、记忆片段和执行候选的最终入选。
- 输入是什么：`hydratedRoundRequest`。
- 输出是什么：重排阶段审计。
- 对下一步有什么影响：可以区分是“未召回”还是“已召回但未重排”。

#### 4.4.4 `assemble` 钩子
- 当前步骤在做什么：记录 `runMainModel` 和 `writeRoundState`。
- 为什么要这么做：同一条流水线会被预工具轮次、挂起轮次、正式轮次复用，必须明确本轮是否要跑主模型、是否要写状态。
- 输入是什么：`hydratedRoundRequest`。
- 输出是什么：组装阶段审计。
- 对下一步有什么影响：为后续判断本轮为什么没有主模型输出提供依据。

### 4.5 调用轮次编排器
- 当前步骤在做什么：把 `hydratedRoundRequest` 交给 `roundPipelineOrchestrator.executeRound(...)`。
- 为什么要这么做：状态驱动流水线负责补齐请求和组织追踪，真正的轮次执行逻辑在 `RoundPipelineOrchestratorImpl` 中。
- 输入是什么：水化后的 `RoundPipelineRequest`。
- 输出是什么：`RoundPipelineResult`。
- 对下一步有什么影响：后续执行后钩子和字段兜底都基于它。

### 4.6 记录执行后钩子
- 当前步骤在做什么：记录 `execute` 钩子中的阻断状态和阻断原因，再记录 `writeback` 钩子中的 `finalSnapshotId` 和 `summaryResult` 是否存在。
- 为什么要这么做：要把真正执行结果写回审计链路，而不是只记录准备阶段信息。
- 输入是什么：`RoundPipelineResult`。
- 输出是什么：执行阶段和回写阶段的审计记录。
- 对下一步有什么影响：为下游判断失败发生在“水化阶段”还是“轮次执行阶段”提供依据。

### 4.7 对轮次结果做兜底封装
- 当前步骤在做什么：若 `result == null`，返回 `round_result_missing`；否则构造新的 `RoundPipelineResult`，优先使用轮次执行结果，缺失字段回退到 `hydratedRoundRequest` 中的 `decision`、`contextPackage`、`reconstructionResult`、`nodeWorksetResult`。
- 为什么要这么做：轮次执行结果可能没有重复回填所有字段，但下游仍然需要完整工件。
- 输入是什么：`result`、`hydratedRoundRequest`。
- 输出是什么：字段尽量完整的 `RoundPipelineResult`。
- 对下一步有什么影响：下游可以稳定继续做工具决策和正式轮次构建。

## 5. StateDrivenContextPipelineImpl.hydrateRoundRequest

### 5.1 提取原始轮次请求
- 当前步骤在做什么：读取 `request.getRoundPipelineRequest()`。
- 为什么要这么做：水化逻辑必须先拿到原始轮次请求作为基底。
- 输入是什么：`StateDrivenContextPipelineRequest`。
- 输出是什么：`input` 或 `null`。
- 对下一步有什么影响：如果原始轮次请求为空，整次水化失败。

### 5.2 统一会话与输入
- 当前步骤在做什么：用 `firstNonBlank(request.getSessionId(), input.getSessionId())` 生成最终 `sessionId`，并用 `safe(input.getUserInput())` 生成 `userInput`。
- 为什么要这么做：外层请求和内层轮次请求都可能携带会话 ID，需要统一优先级。
- 输入是什么：外层请求、内层轮次请求。
- 输出是什么：`sessionId`、`userInput`。
- 对下一步有什么影响：后续补齐决策、上下文、重构结果和节点工作集都基于这两个值。

### 5.3 读取已有核心工件
- 当前步骤在做什么：从 `input` 中读取 `decision`、`contextPackage`、`reconstructionResult`。
- 为什么要这么做：如果上游已经提供这些工件，就不应该重复编排。
- 输入是什么：原始 `RoundPipelineRequest`。
- 输出是什么：当前已有的三类核心工件。
- 对下一步有什么影响：决定是否触发 `orchestrateUserInput` 自动补齐。

### 5.4 条件补齐决策、上下文、重构结果
- 当前步骤在做什么：当 `decision`、`contextPackage`、`reconstructionResult` 任一缺失且 `userInput` 非空时，调用 `taskOrchestratorService().orchestrateUserInput(sessionId, userInput)`，并只回填当前缺失的字段。
- 为什么要这么做：这三类工件是后续节点工作集、工具决策和主模型执行的最小前置条件。
- 输入是什么：`sessionId`、`userInput`、已有工件。
- 输出是什么：补齐后的 `decision`、`contextPackage`、`reconstructionResult`。
- 对下一步有什么影响：为重构就绪性校验和节点工作集生成提供基础。

### 5.5 校验重构结果是否可执行
- 当前步骤在做什么：调用 `isReconstructionReady(reconstructionResult)` 检查重构结果是否达到就绪标准；若不满足，则写入 `STATE_DRIVEN_PIPELINE_BLOCKED` 审计并返回 `null`。
- 为什么要这么做：没有明确任务目标时，后续 RAG 查询、记忆查询、MCP 查询和主模型执行都会失去边界。
- 输入是什么：`reconstructionResult`。
- 输出是什么：继续执行，或终止水化。
- 对下一步有什么影响：只有通过该校验，才会继续生成 `nodeWorksetResult`。

### 5.6 条件生成节点工作集
- 当前步骤在做什么：当 `nodeWorksetResult == null`，且 `userInput` 非空、`decision/contextPackage/reconstructionResult` 全部存在时，调用 `taskOrchestratorService().orchestrateNodeWorkset(...)`。
- 为什么要这么做：节点工作集承接了 RAG 查询、记忆查询、MCP 查询、能力候选和全局重排结果，是正式轮次和工具决策的直接输入。
- 输入是什么：`sessionId`、`userInput`、`decision`、`contextPackage`、`reconstructionResult`。
- 输出是什么：`nodeWorksetResult`。
- 对下一步有什么影响：后续片段池、执行候选和 MCP 提示都以它为高优先级来源。

### 5.7 合并执行候选、知识片段、偏好片段和记忆片段
- 当前步骤在做什么：按 `请求值 > nodeWorksetResult > contextPackage > 空值` 的优先级，分别生成 `executionCandidates`、`mcpResourceHints`、`knowledgeSnippets`、`preferenceSnippets`、`longTermMemorySnippets`、`workingMemorySnippets`、`runtimeMemorySnippets`、`retrievedMemorySnippets`。
- 为什么要这么做：同一轮次可能由不同上游提供不同粒度的覆盖值，必须在这里统一最终片段池。
- 输入是什么：`input`、`nodeWorksetResult`、`contextPackage`。
- 输出是什么：正式可消费的候选集和片段集。
- 对下一步有什么影响：后续工具决策上下文和主模型上下文都以这里的合并结果为准。

### 5.8 解析节点模板策略并组装最终水化请求
- 当前步骤在做什么：若 `input.getNodeTemplatePolicy()` 为空，则调用 `resolveNodeTemplatePolicy(decision, contextPackage)` 自动推导；然后把所有补齐后的字段重新装配为新的 `RoundPipelineRequest`。
- 为什么要这么做：轮次编排器只接受标准化、完整的轮次请求。
- 输入是什么：补齐后的全部治理结果。
- 输出是什么：`hydratedRoundRequest`。
- 对下一步有什么影响：`RoundPipelineOrchestratorImpl.executeRound` 将基于这份完整请求执行轮次逻辑。

## 6. TaskOrchestratorServiceImpl.orchestrateUserInput

### 6.1 第一次上下文编译
- 当前步骤在做什么：调用 `contextCompilerService.compile(sessionId, userInput, null, null)`，得到 `preContextPackage`。
- 为什么要这么做：输入重构不能脱离当前任务状态、关系状态、上下文状态和恢复状态。
- 输入是什么：`sessionId`、`userInput`。
- 输出是什么：第一次编译出的 `StructuredContextPackage`。
- 对下一步有什么影响：输入重构会基于这份初始上下文理解本轮意图。

### 6.2 输入重构
- 当前步骤在做什么：调用 `inputReconstructionAgent.reconstruct(...)`，生成 `InputReconstructionResult`。
- 为什么要这么做：系统后续需要的是显式任务目标、标准化意图、缺失槽位和重构后的检索查询，而不是原始自然语言。
- 输入是什么：`sessionId`、`userInput`、`preContextPackage`、当前任务状态、关系状态。
- 输出是什么：`reconstructionResult`。
- 对下一步有什么影响：状态机推进、节点工作集编排和工具查询生成都依赖这个结果。

### 6.3 构建治理信号
- 当前步骤在做什么：调用 `buildGovernedSignal(userInput, reconstructionResult)` 生成 `GovernedSignal`。
- 为什么要这么做：状态机入口消费的是标准化治理信号，而不是任意自由文本。
- 输入是什么：原始用户输入和输入重构结果。
- 输出是什么：治理信号 JSON。
- 对下一步有什么影响：`eventIngressService.ingestUserInput` 会基于该信号做状态推进和决策。

### 6.4 驱动事件入口服务
- 当前步骤在做什么：调用 `eventIngressService.ingestUserInput(sessionId, userInput, toJsonSafe(governedSignal))`，得到 `OrchestrationDecision`。
- 为什么要这么做：用户输入不只是“多了一句话”，还可能推动任务状态、关系状态、恢复状态和上下文状态发生变化。
- 输入是什么：`sessionId`、`userInput`、治理信号。
- 输出是什么：`decision`。
- 对下一步有什么影响：如果 `decision` 存在，则以 `decision.getContextPackage()` 作为更新后的上下文包。

### 6.5 选择更新后的上下文包
- 当前步骤在做什么：若 `decision == null` 则保留 `preContextPackage`；否则取 `decision.getContextPackage()`。
- 为什么要这么做：状态机执行后可能已经生成更贴近当前轮次的新上下文。
- 输入是什么：`decision`、`preContextPackage`。
- 输出是什么：当前有效的 `contextPackage`。
- 对下一步有什么影响：恢复检测、审计记录和最终返回都基于这份上下文。

### 6.6 检测恢复场景
- 当前步骤在做什么：调用 `resolveRecoveryTrigger(userInput, decision, contextPackage)` 判断是否需要恢复。
- 为什么要这么做：本轮输入可能不是普通新问题，而是在继续、重试、恢复或重规划之前被中断的任务流。
- 输入是什么：用户输入、当前决策、当前上下文。
- 输出是什么：`RecoveryTrigger`。
- 对下一步有什么影响：主链路在这里分成“恢复分支”和“普通推进分支”。

### 6.7 恢复分支
- 当前步骤在做什么：若 `recoveryTrigger.shouldRecover` 为真，则记录恢复状态迁移日志，调用 `recoveryContextAgent.recover(...)`，必要时再调用 `orchestrateNodeWorkset(...)` 做立即刷新，随后写入 `RECOVERY_TRIGGERED` 审计，并在无待处理恢复工作时清理 `recoveryStateStore`。
- 为什么要这么做：恢复分支需要把上下文重定位到可继续执行的状态，同时避免沿用过期检索和过期能力。
- 输入是什么：`sessionId`、`contextPackage`、恢复事件、恢复原因、`reconstructionResult`。
- 输出是什么：恢复后的 `contextPackage`，以及可能刷新后的节点工作集效果。
- 对下一步有什么影响：确保恢复后继续执行的是当前有效上下文，而不是旧快照。

### 6.8 普通推进分支
- 当前步骤在做什么：若不需要恢复，则清理残留恢复状态，并写入 `RECOVERY_SKIPPED` 审计。
- 为什么要这么做：普通轮次不应该误继承上次中断遗留下来的恢复标记。
- 输入是什么：`sessionId`、`userInput`。
- 输出是什么：恢复状态清理和跳过恢复审计。
- 对下一步有什么影响：确保当前轮次被明确标记为普通推进链路。

### 6.9 持久化上下文快照与决策审计
- 当前步骤在做什么：记录最终状态迁移日志、调用 `runtimeAuditService.persistContextSnapshot(sessionId, contextPackage)`，再写入 `ORCHESTRATION_DECISION` 和 `INPUT_RECONSTRUCTION` 审计。
- 为什么要这么做：任务编排阶段是整条链路的治理入口，必须留下决策依据和重构结果。
- 输入是什么：最终 `contextPackage`、`decision`、`reconstructionResult`。
- 输出是什么：上下文快照和两类关键审计记录。
- 对下一步有什么影响：为后续节点工作集编排、正式轮次执行和问题排查提供可追溯基础。

### 6.10 返回任务编排结果
- 当前步骤在做什么：返回 `TaskOrchestrationResult`，其中包含 `decision`、`contextPackage`、`reconstructionResult`、`recovered`、`recoveryEvent`、`interruptReason`。
- 为什么要这么做：下游只需要消费标准化任务编排结果，而不需要理解恢复处理细节。
- 输入是什么：本阶段全部产物。
- 输出是什么：`TaskOrchestrationResult`。
- 对下一步有什么影响：`hydrateRoundRequest` 会从这里提取核心工件，并继续做节点工作集补齐。

## 7. TaskOrchestratorServiceImpl.orchestrateNodeWorkset

### 7.1 召回就绪性校验
- 当前步骤在做什么：调用 `evaluateReconstructionRecallGate(reconstructionResult, decision.getTaskState())` 判断是否具备召回条件。
- 为什么要这么做：如果任务目标不明确、缺槽位过多或意图置信度不足，直接做召回只会污染上下文。
- 输入是什么：`reconstructionResult`、当前任务状态。
- 输出是什么：`ReconstructionRecallGate`，或阻断结果。
- 对下一步有什么影响：若未通过，则写 `NODE_WORKSET_BLOCKED` 审计并返回 `blockedNodeWorksetResult(reason)`。

### 7.2 构建追踪元数据并记录召回阶段日志
- 当前步骤在做什么：生成 `worksetTraceId`、`traceMeta`，并记录 `recall` 状态迁移日志。
- 为什么要这么做：节点工作集内部包含多路召回、MCP 预排序和全局重排，需要独立链路追踪。
- 输入是什么：`sessionId`、`contextPackage`、任务状态。
- 输出是什么：节点工作集追踪上下文。
- 对下一步有什么影响：后续 RAG、记忆、MCP 相关审计会共享这些追踪元数据。

### 7.3 生成 MCP 查询并消费恢复刷新计划
- 当前步骤在做什么：先调用 `consumeRecoveryRefreshPlan(contextPackage)` 获取刷新计划，再调用 `mcpQueryBuilder.build(reconstructionResult, decision.getTaskState())` 生成 `mcpDrivenInput`；必要时附加 `reassembly`、`mcp` 刷新标记。
- 为什么要这么做：节点工作集不仅要决定“查什么”，还要决定“这轮是否应该强制刷新旧能力和旧组装结果”。
- 输入是什么：恢复刷新计划、输入重构结果、任务状态。
- 输出是什么：最终 `mcpDrivenInput`。
- 对下一步有什么影响：如果 MCP 查询无法构建，则整个节点工作集在这里阻断。

### 7.4 MCP 预路由与预排序
- 当前步骤在做什么：先通过 `capabilityPolicyRouterService.routeForContext(...)` 取最多 24 个原始 MCP 候选，再过滤失效能力，最后通过 `mcpCandidatePreRank.preRank(...)` 得到 `mcpPreRankedCandidates`。
- 为什么要这么做：全局重排前，需要先把能力候选池缩小到更可信、更相关的一批。
- 输入是什么：`sessionId`、`mcpDrivenInput`、任务状态、关系状态。
- 输出是什么：MCP 候选池和预排序结果。
- 对下一步有什么影响：这些候选会与 RAG、记忆召回结果一起进入全局上下文重排。

### 7.5 构建 RAG 查询和记忆查询
- 当前步骤在做什么：分别调用 `ragQueryBuilder.build(...)` 和 `memoryQueryBuilder.build(...)` 生成 `ragQuery`、`memoryQuery`。
- 为什么要这么做：知识检索和记忆检索虽然都会进入工作集，但查询目标不同，必须分别生成。
- 输入是什么：`reconstructionResult`、当前任务状态。
- 输出是什么：两类查询字符串。
- 对下一步有什么影响：任一查询无法构建，都会导致工作集阻断。

### 7.6 执行多路检索
- 当前步骤在做什么：分别执行知识/偏好检索和记忆检索，然后合并两路响应，并过滤失效证据。
- 为什么要这么做：知识、偏好、记忆属于不同来源，不能依赖单一路径召回。
- 输入是什么：`ragQuery`、`memoryQuery`、会话上下文、允许检索路由、检索选项。
- 输出是什么：合并后的 `RetrievalResponse`。
- 对下一步有什么影响：全局重排会从这里消费原始知识候选、偏好候选和记忆候选。

### 7.7 记录召回与底部重排审计
- 当前步骤在做什么：写入 `MULTI_ROUTE_RECALL_TRACE` 和 `RERANK_TRACE_BOTTOM_CHANNELS` 审计，记录各通道原始候选和底部重排信息。
- 为什么要这么做：后续若全局重排结果异常，需要先能还原原始候选池。
- 输入是什么：两路检索结果、MCP 预排序候选、查询串。
- 输出是什么：两类审计记录。
- 对下一步有什么影响：为全局重排结果诊断提供底层证据。

### 7.8 执行全局上下文重排
- 当前步骤在做什么：调用 `globalContextRerankAgent.rerank(...)`，把检索结果和 MCP 候选统一做跨源重排。
- 为什么要这么做：最终进入主模型和工具决策的上下文必须是跨知识、记忆、能力候选统一排序后的结果。
- 输入是什么：`reconstructionResult`、`contextPackage`、检索响应、`mcpPreRankedCandidates`、任务状态。
- 输出是什么：`rerankResult`。
- 对下一步有什么影响：知识证据块、记忆片段、执行候选和 MCP 资源提示都优先从 `rerankResult` 中提取。

### 7.9 解析知识、记忆、偏好片段
- 当前步骤在做什么：知识优先取 `rerankResult.getSelectedKnowledgeEvidenceBlocks()`，其次取 `getSelectedKnowledgeBlocks()`，最后降级到原始召回结果；记忆合并原始召回片段和 `rerankResult.getSelectedMemoryHints()`；偏好从响应中提取。
- 为什么要这么做：全局重排结果优先级最高，但仍需保留降级路径。
- 输入是什么：`rerankResult`、原始检索响应。
- 输出是什么：`selectedKnowledgeEvidenceBlocks`、`selectedKnowledge`、`selectedMemory`、`selectedPreference`。
- 对下一步有什么影响：这些片段直接决定工具决策上下文和正式轮次上下文的事实边界。

### 7.10 异常降级
- 当前步骤在做什么：如果召回或重排过程中抛异常，则写入 `NODE_ORCHESTRATION_RECALL_FAILED` 审计，并把知识、记忆、偏好结果降级为空列表。
- 为什么要这么做：节点工作集阶段尽量避免因为单个召回分支异常就让整轮对话完全失败。
- 输入是什么：异常对象和当前查询信息。
- 输出是什么：带降级空结果的工作集分支。
- 对下一步有什么影响：后续仍可继续工具决策和正式轮次，只是上下文会更弱。

### 7.11 解析执行候选与 MCP 资源提示
- 当前步骤在做什么：调用 `resolveExecutionCandidates(...)` 生成最终 `executionCandidates`；调用 `mcpResourceHintExtractor.extract(...)` 生成 `mcpResourceHints`；同时提取工具、Prompt、资源、Workflow 的候选名称列表。
- 为什么要这么做：后续工具决策不直接消费底层原始候选，而是消费已经归一化、可执行的候选池。
- 输入是什么：`rerankResult`、`mcpPreRankedCandidates`、恢复刷新计划。
- 输出是什么：执行候选资源、MCP 资源提示及各类候选名称。
- 对下一步有什么影响：正式返回的 `NodeWorksetResult` 会成为工具决策节点和正式聊天轮次的关键输入。

### 7.12 返回节点工作集结果
- 当前步骤在做什么：构造并返回 `NodeWorksetResult`，包含 `mcpDrivenInput`、`ragQuery`、`memoryQuery`、`mcpPreRankedCandidates`、`rerankResult`、知识证据、知识片段、记忆片段、偏好片段、各类候选名称、失效证据信息、`executionCandidates`、`mcpResourceHints`。
- 为什么要这么做：下游需要一个聚合后的节点级工作集对象，而不是零散中间结果。
- 输入是什么：本阶段全部召回、重排、候选解析结果。
- 输出是什么：`NodeWorksetResult`。
- 对下一步有什么影响：`ChatServiceImpl.chat` 会基于它提取片段池并执行工具决策。

## 8. ChatServiceImpl.chat 的工具决策前处理

### 8.1 从 `contextPackage` 和 `nodeWorkset` 中拆出片段池
- 当前步骤在做什么：提取 `knowledgeSnippets`、`preferenceSnippets`、`longTermMemorySnippets`、`workingMemorySnippets`、`runtimeMemorySnippets`、`executionCandidates`、`mcpResourceHints`、`ragMemorySnippets`、`knowledgeEvidenceBlocks`。
- 为什么要这么做：后续工具决策和正式轮次都不直接消费整个 `contextPackage`，而是消费拆平后的片段池。
- 输入是什么：`contextPackage`、`nodeWorkset`。
- 输出是什么：各类片段和候选集。
- 对下一步有什么影响：工具决策节点将基于这些片段组装自己的决策上下文。

### 8.2 覆盖知识片段并合并偏好片段
- 当前步骤在做什么：如果 `nodeWorkset.getSelectedKnowledgeSnippets()` 非空，则覆盖默认知识片段；偏好片段通过 `mergeDistinct(...)` 与节点工作集中的偏好去重合并。
- 为什么要这么做：节点工作集已经完成面向当前轮次的筛选，优先级高于原始上下文提取结果。
- 输入是什么：默认知识/偏好片段和节点工作集筛选结果。
- 输出是什么：最终知识片段和偏好片段。
- 对下一步有什么影响：工具决策上下文会使用更聚焦的事实集合。

## 9. TaskOrchestratorServiceImpl.orchestrateToolDecisionNode

### 9.1 规范化输入并抽取工作集
- 当前步骤在做什么：安全化 `sessionId`、`userInput`，再从 `nodeWorksetResult` 中读取 `rerankResult`、`mcpDrivenInput`、`executionCandidates`、`mcpResourceHints`、知识片段、偏好片段、长期记忆、工作记忆、运行时记忆、RAG 记忆、知识证据块。
- 为什么要这么做：工具决策阶段消费的是节点级工作集，而不是重新从上下文包做一遍粗提取。
- 输入是什么：会话、原始输入、`decision`、`contextPackage`、`reconstructionResult`、`nodeWorksetResult`。
- 输出是什么：工具决策所需的完整工作集字段。
- 对下一步有什么影响：这些字段会进入工具决策专用的上下文组装。

### 9.2 解析节点模板策略并构建节点级记忆片段
- 当前步骤在做什么：先调用 `resolveNodeTemplatePolicy(decision, contextPackage)`，再调用 `buildNodeScopedMemorySnippets(...)` 生成 `memorySnippets`。
- 为什么要这么做：工具决策并不需要全部记忆，而需要受节点模板约束后的局部记忆视图。
- 输入是什么：任务状态、上下文包、工作记忆、运行时记忆、检索记忆、长期记忆。
- 输出是什么：`nodeTemplatePolicy`、`memorySnippets`。
- 对下一步有什么影响：这会决定工具决策时看到哪些记忆片段。

### 9.3 组装工具决策专用上下文
- 当前步骤在做什么：构建 `ContextNodeTemplatePolicy.forToolDecision(...)`，再调用 `contextAssembler.assemble(...)` 生成 `assembledDecisionContext`。
- 为什么要这么做：工具决策需要自己的 prompt 视图，不能直接复用正式回复轮次的上下文模板。
- 输入是什么：`contextPackage`、`reconstructionResult`、`rerankResult`、知识证据、各类记忆片段、知识片段、偏好片段、执行候选、MCP 提示。
- 输出是什么：`assembledDecisionContext`。
- 对下一步有什么影响：后续工具调用治理和输入签名都基于这份决策上下文。

### 9.4 写入 `ToolCallingContextHolder`
- 当前步骤在做什么：把会话、原始输入、工具决策输入、签名、组装后的决策上下文、记忆片段、知识片段、偏好片段、长期记忆、执行候选、MCP 提示、执行轨迹列表写入 `ToolCallingContextHolder`。
- 为什么要这么做：工具调用链中的多个组件需要共享统一治理上下文和轨迹容器。
- 输入是什么：工具决策上下文和工作集字段。
- 输出是什么：当前线程的工具调用上下文。
- 对下一步有什么影响：真正执行工具时，下游可以直接从 ThreadLocal 读取这些治理信息。

### 9.5 保存工具决策前后的上下文快照
- 当前步骤在做什么：保存 `preToolSnapshotId` 和 `toolDecisionSnapshotId`，并写入 `CONTEXT_SNAPSHOT_PRE_TOOL` 审计。
- 为什么要这么做：工具决策前后的上下文是后续追查“为什么选了某个工具”的关键依据。
- 输入是什么：会话、计划、节点、原始输入、`mcpDrivenInput`、执行候选、决策上下文。
- 输出是什么：两份快照 ID 和一条审计记录。
- 对下一步有什么影响：挂起分支和正式轮次会把这些快照 ID 作为恢复锚点或追溯依据。

### 9.6 执行工具调用
- 当前步骤在做什么：调用 `agentService.processToolCallingWithGovernance(ToolDecisionCommand.builder()...)` 真正执行工具决策与工具链。
- 为什么要这么做：到这一步才从“准备上下文”切换到“实际调用工具”。
- 输入是什么：会话、原始用户输入、`mcpDrivenInput`、prompt 绑定信息、任务状态、关系状态、执行候选、输入签名、决策上下文。
- 输出是什么：原始 `toolContext`，或异常。
- 对下一步有什么影响：工具执行结果会被持久化、语义化，并决定是进入挂起分支还是继续正式回复分支。

### 9.7 在 finally 中持久化工具轨迹并上报工具结果
- 当前步骤在做什么：无论成功失败，都从 `ToolCallingContextHolder` 读取轨迹，调用 `persistToolExecutionTraces(...)` 写入轨迹，然后调用 `eventIngressService.ingestToolResult(...)` 上报工具执行结果，最后清理 `ToolCallingContextHolder`。
- 为什么要这么做：工具执行失败也必须留下轨迹和状态，否则状态机无法感知工具结果。
- 输入是什么：工具执行结果、异常信息、执行耗时、执行轨迹。
- 输出是什么：`ToolTraceRefs` 和一条工具结果上报。
- 对下一步有什么影响：后续会用这些原始引用构建 `rawToolResultChannel` 并生成工具语义。

### 9.8 构建原始工具结果通道
- 当前步骤在做什么：调用 `buildRawToolResultChannel(toolContext, latestToolExecutionTraces, latestRawRef, historyRefs)`，生成包含 `rawToolContext`、`rawToolExecutionTraces`、`latestToolRawRef`、`toolHistoryRefs` 的 `rawToolResultChannel`。
- 为什么要这么做：后续工具语义翻译、正式轮次、状态写回都需要统一的原始工具结果通道。
- 输入是什么：工具执行上下文和工具轨迹引用。
- 输出是什么：`rawToolResultChannel`。
- 对下一步有什么影响：工具语义翻译和正式轮次会优先消费它。

### 9.9 生成工具语义
- 当前步骤在做什么：调用 `resolveToolSemanticFromRequest(...)`，把工具执行结果转成 `ToolSemanticResult`。
- 为什么要这么做：主模型和状态写回更适合消费结构化工具语义，而不是生的工具返回内容。
- 输入是什么：会话、上下文包、任务状态、显式任务目标、执行候选、原始 `toolContext`、阶段、`rawToolResultChannel`。
- 输出是什么：`toolSemanticResult`。
- 对下一步有什么影响：后续工具上下文融合、正式轮次组装、状态写回都会使用它。

### 9.10 立即写回工具语义状态
- 当前步骤在做什么：调用 `persistImmediateToolSemanticState(...)`，把工具语义和原始引用先写回当前状态。
- 为什么要这么做：即使主链路后续转入挂起或失败，工具阶段结果也应该先沉淀。
- 输入是什么：会话、上下文包、工具语义、原始工具通道、历史引用。
- 输出是什么：即时写回的工具状态。
- 对下一步有什么影响：挂起分支、正式聊天轮次和后续恢复链路都能读取到最新工具状态。

### 9.11 返回工具决策节点结果
- 当前步骤在做什么：返回 `ToolDecisionNodeResult`，包含 `toolContext`、`rawToolResultChannel`、`toolTraceRefs`、`toolSemantic`、`preToolSnapshotId`、`toolDecisionSnapshotId`。
- 为什么要这么做：`ChatServiceImpl.chat` 需要统一消费工具执行的所有产物。
- 输入是什么：本阶段的全部结果。
- 输出是什么：`ToolDecisionNodeResult`。
- 对下一步有什么影响：`ChatServiceImpl.chat` 会基于它构造 `mergedToolContext`，并判断是否进入挂起分支。

## 10. ChatServiceImpl.chat 的工具后处理与分支

### 10.1 提取工具阶段产物
- 当前步骤在做什么：从 `toolDecisionNodeResult` 中提取 `toolContext`、`toolSemanticResult`、`rawToolResultChannel`、`toolDecisionSnapshotId`、`latestToolRawRef`、`latestToolHistoryRefs`、`latestToolExecutionTraces`。
- 为什么要这么做：工具调用产物后续会同时流向挂起分支和正式轮次分支。
- 输入是什么：`toolDecisionNodeResult`。
- 输出是什么：结构化的工具阶段结果。
- 对下一步有什么影响：后续工具上下文融合和挂起判断都依赖这些字段。

### 10.2 生成综合摘要并合并工具上下文
- 当前步骤在做什么：先调用 `threeStageResponseService.generateSynthesisBrief(input, toolContext, contextPackage)` 生成 `synthesisBrief`，再调用 `mergeToolContextWithSemantic(...)` 融合工具语义，最后调用 `mergeToolContextWithSynthesis(...)` 生成最终 `mergedToolContext`，并写入 `RESPONSE_SYNTHESIS` 审计。
- 为什么要这么做：原始工具结果通常过于底层，正式轮次更适合消费带有业务语义和综合摘要的工具事实。
- 输入是什么：用户输入、原始工具上下文、工具语义、结构化上下文包。
- 输出是什么：`mergedToolContext`。
- 对下一步有什么影响：挂起判断基于它，正式聊天轮次也基于它。

### 10.3 判断是否异步挂起
- 当前步骤在做什么：调用 `isAsyncPending(mergedToolContext)`，检查其 JSON 中 `status` 是否为 `pending`。
- 为什么要这么做：有些工具不是同步完成，而是先返回后台处理中，需要走“挂起后等待回调”的分支。
- 输入是什么：`mergedToolContext`。
- 输出是什么：布尔值。
- 对下一步有什么影响：主链路在这里分叉为“挂起分支”和“正式聊天轮次分支”。

## 11. ChatServiceImpl.chat 挂起分支

### 11.1 构造挂起回复
- 当前步骤在做什么：调用 `buildPendingReply(mergedToolContext)` 生成包含 `emotion`、`reply`、`status=pending`、`taskId`、`workflowName` 的 JSON 回复。
- 为什么要这么做：前端需要立刻给用户一个可展示的“后台处理中”答复。
- 输入是什么：`mergedToolContext`。
- 输出是什么：`pendingReply`。
- 对下一步有什么影响：该回复既会返回给前端，也可能参与记忆写入。

### 11.2 缓存待恢复工具调用
- 当前步骤在做什么：调用 `cachePendingToolCall(runtimeSessionId, mergedToolContext)`，把 `taskId`、`workflowName`、`skillName`、`status`、`toolContext` 写入 Redis 热层。
- 为什么要这么做：后台工具回调回来时，需要根据会话和任务 ID 找到等待恢复的链路锚点。
- 输入是什么：会话 ID、`mergedToolContext`。
- 输出是什么：一条 `pending_tool_call` 热层缓存记录。
- 对下一步有什么影响：后续回调链路可以根据缓存恢复本轮执行。

### 11.3 评估挂起轮次是否允许写记忆
- 当前步骤在做什么：调用 `evaluateMemoryWriteGate(input, pendingReply, reconstruction, toolSemanticResult, true)`，挂起轮次阈值为 `0.35`。
- 为什么要这么做：挂起轮次没有最终正式回复，但有些高价值中间状态仍值得沉淀。
- 输入是什么：用户输入、挂起回复、输入重构、工具语义、`pendingTurn=true`。
- 输出是什么：`MemoryWriteGateDecision`。
- 对下一步有什么影响：决定是否执行挂起轮次记忆写入。

### 11.4 条件执行挂起轮次记忆写入
- 当前步骤在做什么：如果 `pendingGate.allowWrite()`，调用 `memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, pendingReply, contextPackage)`。
- 为什么要这么做：把“已进入后台执行”这一事实沉淀下来，供后续上下文恢复使用。
- 输入是什么：会话、用户输入、挂起回复、上下文包。
- 输出是什么：挂起轮次记忆写入结果。
- 对下一步有什么影响：下一轮上下文编译可能命中这一挂起态信息。

### 11.5 触发 `CHAT_TURN_PENDING` 状态驱动流水线
- 当前步骤在做什么：再次调用 `stateDrivenContextPipeline.run(...)`，构建 `stage=CHAT_TURN_PENDING` 的 `RoundPipelineRequest`，关键参数包括：`runMainModel=false`、`assistantReplyOverride=pendingReply`、`writeRoundState=true`、`latestSnapshotId=toolDecisionSnapshotId`、`latestToolRawRef=latestToolRawRef`、`latestToolHistoryRefs=latestToolHistoryRefs`、`rawToolResultChannel=buildRawToolResultChannel(...)`，以及 `retrievalPlanOverrides.pending=true`、`nextActionHint=await_tool_callback`、`pendingRecoveryAnchor=toolDecisionSnapshotId`。
- 为什么要这么做：虽然本轮不生成正式回复，但仍需要把“挂起中”状态写回状态仓库，给恢复链路留下锚点。
- 输入是什么：会话、治理工件、工具语义、片段池、挂起回复、工具引用、挂起状态补丁。
- 输出是什么：一次挂起状态轮次执行结果。
- 对下一步有什么影响：状态仓库会落下 `pending=true` 及恢复锚点，后续等待工具回调继续推进。

### 11.6 结束挂起轮次并返回
- 当前步骤在做什么：发布 `IDLE`，然后返回 `ResponseEntity.ok(tryParseJsonNode(pendingReply))`。
- 为什么要这么做：HTTP 请求在挂起分支中已经完成了本轮应做的所有同步工作。
- 输入是什么：`pendingReply`。
- 输出是什么：挂起响应。
- 对下一步有什么影响：本轮主链路在这里结束；正式主模型不会在本次请求中执行。

## 12. ChatServiceImpl.chat 正式聊天轮次分支

### 12.1 发布整理状态
- 当前步骤在做什么：调用 `statusPublisher.publish(DEFAULT_CLIENT_ID, STATUS_THINKING, VALUE_THINKING_ORGANIZE)`。
- 为什么要这么做：工具结果已拿到，接下来进入正式回复生成前的上下文整理阶段。
- 输入是什么：状态常量。
- 输出是什么：状态事件。
- 对下一步有什么影响：前端可区分“检索中”和“整理生成中”。

### 12.2 保存旧的 `ContextState`
- 当前步骤在做什么：读取 `previousContextState = contextPackage.getContextState()`。
- 为什么要这么做：后续 `persistReplayAndMemoryGovernance` 需要比较前后快照。
- 输入是什么：`contextPackage`。
- 输出是什么：`previousContextState`。
- 对下一步有什么影响：后续质量回放与记忆治理需要它做基线。

### 12.3 构建正式 `CHAT_TURN` 轮次请求
- 当前步骤在做什么：组装 `stage=CHAT_TURN` 的正式轮次请求，带入 `decision`、`contextPackage`、`reconstructionResult`、`nodeWorksetResult`、`toolSemanticResult`、各类记忆片段和知识片段、`executionCandidates`、`mcpResourceHints`、`nodeTemplatePolicy`、`toolContext=mergedToolContext`、`runMainModel=true`、`assistantReplyOverride=""`、`replaceHistoryWithSummary=true`、`writeRoundState=true`、`latestToolRawRef`、`latestToolHistoryRefs`、`rawToolResultChannel`。
- 为什么要这么做：到这一步，正式回复所需的治理工件、工具语义和工具结果都已经齐备，可以进入真正的主模型轮次。
- 输入是什么：预工具工件、工具阶段工件、片段池、控制参数。
- 输出是什么：正式聊天轮次请求。
- 对下一步有什么影响：该请求会再次进入状态驱动流水线，但这次会真正执行主模型和状态写回。

### 12.4 再次调用 `stateDrivenContextPipeline.run`
- 当前步骤在做什么：以 `triggerSource=CHAT_TURN` 调用 `stateDrivenContextPipeline.run(...)`。
- 为什么要这么做：正式轮次仍然复用统一的状态驱动流水线和轮次编排器，只是这次 `runMainModel=true`。
- 输入是什么：正式聊天轮次请求。
- 输出是什么：`roundPipelineResult`。
- 对下一步有什么影响：最终回复、摘要、快照、轮次状态写回结果都从这里取。

## 13. RoundPipelineOrchestratorImpl.executeRound

### 13.1 校验轮次请求
- 当前步骤在做什么：若 `request == null`，直接返回 `blocked=true, blockedReason=round_request_missing` 的 `RoundPipelineResult`。
- 为什么要这么做：轮次执行器不能在无请求对象的情况下继续构造状态和审计结果。
- 输入是什么：`RoundPipelineRequest`。
- 输出是什么：阻断结果，或继续执行。
- 对下一步有什么影响：正式轮次和挂起轮次都会先经过这里的基础校验。

### 13.2 提取轮次工作集
- 当前步骤在做什么：提取 `sessionId`、`contextPackage`、`planId`、`nodeId`、`traceId`、`nodeWorksetResult`、`rerankResult`，并解析 `executionCandidates`、`mcpResourceHints`、`knowledgeEvidenceBlocks`。
- 为什么要这么做：后续工具语义补齐、预摘要、主模型执行、后摘要和状态写回都要复用这些统一工作集。
- 输入是什么：`RoundPipelineRequest`。
- 输出是什么：轮次执行上下文。
- 对下一步有什么影响：如果请求中没显式带某些字段，会回退到 `nodeWorksetResult` 中的值。

### 13.3 条件补齐工具语义
- 当前步骤在做什么：若 `request.getToolSemanticResult() == null`，则调用 `resolveToolSemantic(...)` 做工具语义翻译。
- 为什么要这么做：正式主模型阶段需要结构化工具语义，即使上游没传，也要在轮次内部补齐。
- 输入是什么：会话、上下文包、任务状态、显式任务目标、执行候选、工具上下文、阶段、原始工具通道。
- 输出是什么：`effectiveToolSemantic`。
- 对下一步有什么影响：主模型上下文组装、摘要生成、状态写回都会消费这份语义结果。

### 13.4 初始化轮次输出槽位
- 当前步骤在做什么：初始化 `preAssemblySummary`、`modelResult`、`assistantReply`、`finalSnapshotId`。
- 为什么要这么做：后续根据是否运行主模型来分别填充这些结果。
- 输入是什么：请求中的 `assistantReplyOverride`、`latestSnapshotId`。
- 输出是什么：初始结果变量。
- 对下一步有什么影响：若本轮不跑主模型，则 `assistantReply` 可能直接沿用 override。

### 13.5 条件执行主模型分支
- 当前步骤在做什么：当 `request.isRunMainModel()` 为真时，先调用 `taskOrchestratorService().orchestrateSummary(..., replaceHistory=false)` 生成 `preAssemblySummary`，再构造 `MainModelExecutionRequest` 调用 `taskOrchestratorService().orchestrateMainModel(...)`。
- 为什么要这么做：正式用户可见回复只在这里生成，预摘要用于先压缩上下文。
- 输入是什么：会话、用户输入、上下文包、重构结果、重排结果、工具语义、知识证据、各类记忆片段、知识片段、偏好片段、长期记忆、执行候选、MCP 提示、工具上下文、节点模板策略、预摘要、计划/节点 ID、阶段、修复种子、原始工具通道。
- 输出是什么：`preAssemblySummary` 和 `modelResult`。
- 对下一步有什么影响：若 `modelResult == null` 或 `modelResult.isBlocked()`，轮次直接阻断，不再进入后摘要和状态写回。

### 13.6 更新正式回复和最终快照
- 当前步骤在做什么：若主模型执行成功，则用 `modelResult.getReplyText()` 更新 `assistantReply`，并用 `modelResult.getFinalSnapshotId()` 更新 `finalSnapshotId`。
- 为什么要这么做：后摘要和状态写回需要正式回复和最终快照。
- 输入是什么：`modelResult`。
- 输出是什么：更新后的 `assistantReply`、`finalSnapshotId`。
- 对下一步有什么影响：后摘要会以正式回复作为 assistantReply 输入。

### 13.7 生成轮次后摘要
- 当前步骤在做什么：调用 `taskOrchestratorService().orchestrateSummary(..., assistantReply, replaceHistoryWithSummary=request.isReplaceHistoryWithSummary())`，得到 `summaryResult`。
- 为什么要这么做：后摘要既服务于当前轮次状态写回，也服务于下一轮上下文压缩和历史替换。
- 输入是什么：会话、用户输入、当前 `assistantReply`、上下文包、知识证据、MCP 提示、工具语义、是否替换历史。
- 输出是什么：`summaryResult`。
- 对下一步有什么影响：若 `writeRoundState=true`，状态写回将使用它。

### 13.8 条件执行轮次状态写回
- 当前步骤在做什么：若 `request.isWriteRoundState()` 为真，则调用 `taskOrchestratorService().writeRoundState(...)`，带入决策、上下文、重构、重排结果、工具语义、摘要结果、最新快照 ID、最新工具引用、原始工具通道、RAG/Memory/MCP 查询、检索计划覆盖项。
- 为什么要这么做：轮次执行后，必须把任务状态、检索状态、工具状态、上下文状态同步到状态仓库，维持后续轮次连续性。
- 输入是什么：本轮全部治理和执行结果。
- 输出是什么：状态仓库中的多类状态更新。
- 对下一步有什么影响：下一轮上下文编译、恢复逻辑、工具回放和活跃引用治理都会基于这些状态。

### 13.9 返回轮次执行结果
- 当前步骤在做什么：记录 `writeback` 状态迁移日志，然后返回包含 `toolSemanticResult`、`preAssemblySummary`、`mainModelResult`、`summaryResult`、`finalSnapshotId`、`decision`、`contextPackage`、`reconstructionResult`、`nodeWorksetResult` 的 `RoundPipelineResult`。
- 为什么要这么做：轮次编排器要对外提供完整阶段性产物，而不是只返回最终回复。
- 输入是什么：本轮执行的全部结果。
- 输出是什么：标准化 `RoundPipelineResult`。
- 对下一步有什么影响：`ChatServiceImpl.chat` 会从中提取最终回复、摘要、快照和工具语义并做最终收口。

## 14. TaskOrchestratorServiceImpl.orchestrateMainModel

### 14.1 校验请求并提取上下文
- 当前步骤在做什么：若 `request == null`，直接返回阻断结果；否则提取 `sessionId`、`contextPackage`、`planId`、`nodeId`、`rawToolResultChannel`、`activeRefs`。
- 为什么要这么做：主模型执行前必须具备完整上下文、快照引用和原始工具通道。
- 输入是什么：`MainModelExecutionRequest`。
- 输出是什么：主模型执行基础上下文。
- 对下一步有什么影响：后续上下文组装、Prompt 组装和主模型调用都用这些字段。

### 14.2 记录 assemble 状态迁移
- 当前步骤在做什么：写一条 `assemble` 状态迁移日志。
- 为什么要这么做：主模型执行链路需要独立记录“开始做上下文组装”的时点。
- 输入是什么：任务状态、上下文快照 ID、恢复事件。
- 输出是什么：状态迁移记录。
- 对下一步有什么影响：便于将主模型前的组装阶段与真正执行阶段区分开。

### 14.3 解析主模型 Prompt 治理结果
- 当前步骤在做什么：调用 `resolveMainModelPromptAssembly(request.getUserInput(), contextPackage, request.getNodeTemplatePolicy())`。
- 为什么要这么做：最终 prompt 的模板选择和治理规则不能硬编码，需要按当前上下文动态决策。
- 输入是什么：用户输入、上下文包、节点模板策略。
- 输出是什么：`PromptResolveResult`。
- 对下一步有什么影响：`contextAssembler.assembleAndSnapshot(...)` 会依据这个治理结果组织最终上下文。

### 14.4 组装最终主模型上下文并生成快照
- 当前步骤在做什么：调用 `contextAssembler.assembleAndSnapshot(...)`，把重构结果、重排结果、工具语义、知识证据、各类记忆、知识片段、偏好、执行候选、MCP 提示、工具上下文、轮次摘要、原始工具通道、活跃引用、恢复载荷、Prompt 治理结果全部组装进 `AssembledContext`，并得到 `finalSnapshotId`。
- 为什么要这么做：主模型不应该直接面对零散字段，而应该消费按模板、证据、记忆、工具事实整理好的统一上下文。
- 输入是什么：主模型执行请求中的全部治理结果。
- 输出是什么：`assembledContext`、`finalSnapshotId`。
- 对下一步有什么影响：如果这里组装不出有效 prompt，主模型会被直接阻断。

### 14.5 记录最终上下文快照审计
- 当前步骤在做什么：写 `contextTraceLogger` 日志，并通过 `runtimeAuditService.persistDecisionRecord(..., "CONTEXT_SNAPSHOT_FINAL", ...)` 持久化最终快照 ID。
- 为什么要这么做：正式回复使用的是哪份最终上下文，必须可回放、可追溯。
- 输入是什么：`assembledContext`、`finalSnapshotId`。
- 输出是什么：上下文追踪和最终快照审计。
- 对下一步有什么影响：后续如需分析回答质量，可回放到这份最终快照。

### 14.6 校验最终 Prompt
- 当前步骤在做什么：读取 `finalPrompt = assembledContext.getPrompt()`；若为空，则写 `CONTEXT_GOVERNANCE_BLOCKED` 审计并返回 `blockedReason=final_governed_workset_empty`。
- 为什么要这么做：如果最终治理后的工作集为空，再调用主模型只会得到不可信结果。
- 输入是什么：`assembledContext`。
- 输出是什么：阻断结果或有效的 `finalPrompt`。
- 对下一步有什么影响：只有 prompt 非空，才会真正调用主模型。

### 14.7 调用主模型
- 当前步骤在做什么：调用 `invokeMainModel(finalPrompt, repairSeed, contextPackage, sessionId, roundId, nodeId, finalSnapshotId)`。
- 为什么要这么做：这是整个正式聊天轮次中真正生成用户可见回复的时刻。
- 输入是什么：最终 prompt、修复种子、上下文、会话、轮次 ID、节点 ID、最终快照 ID。
- 输出是什么：`ModelReply`，包含 `raw`、`valid`、`replyText`。
- 对下一步有什么影响：后续返回值、日志覆盖、摘要生成和记忆写入都基于这份回复。

### 14.8 记录 writeback 状态迁移并持久化 Prompt 快照引用
- 当前步骤在做什么：写 `writeback` 状态迁移日志；若 `sessionId` 非空，则调用 `persistPromptSnapshotRefs(...)`。
- 为什么要这么做：主模型执行完成后，需要把 prompt 快照和上下文快照建立引用关系。
- 输入是什么：会话、轮次 ID、节点 ID、最终快照 ID、`assembledContext`。
- 输出是什么：快照引用关系和主模型写回阶段状态记录。
- 对下一步有什么影响：后续审计和回放可以从快照引用反查主模型输入。

### 14.9 返回主模型编排结果
- 当前步骤在做什么：返回 `MainModelOrchestrationResult`，包含 `assembledContext`、`finalSnapshotId`、`finalPrompt`、`rawResponse`、`validResponse`、`replyText`。
- 为什么要这么做：轮次编排器和 `ChatServiceImpl.chat` 需要完整消费主模型执行结果。
- 输入是什么：`assembledContext`、`ModelReply`。
- 输出是什么：`MainModelOrchestrationResult`。
- 对下一步有什么影响：`ChatServiceImpl.chat` 会从中拿到最终对用户返回的内容。

## 15. TaskOrchestratorServiceImpl.writeRoundState

### 15.1 校验写回请求并读取旧状态
- 当前步骤在做什么：校验 `request` 和 `sessionId`，然后从 `contextPackage` 中读取旧的 `TaskState`、`RetrievalState`、`ToolState`、`ContextState`，同时提取运行时 `session` 行和活跃工具结果。
- 为什么要这么做：状态写回不是盲目覆盖，而是基于上一轮状态做增量合并。
- 输入是什么：`RoundStateWriteRequest`。
- 输出是什么：旧状态基线和运行时补充信息。
- 对下一步有什么影响：后续所有状态对象都会做“旧值 + 本轮增量”的合并。

### 15.2 写回 `TaskState`
- 当前步骤在做什么：合并已完成步骤、失败步骤、重试次数、已确认槽位、待澄清问题、目标、当前阶段、当前节点和 `nextActionHint`，然后保存到 `taskStateStore`。
- 为什么要这么做：任务状态是下一轮编排和恢复逻辑的核心输入。
- 输入是什么：旧任务状态、运行时工具结果、重构结果、摘要快照、检索计划补丁。
- 输出是什么：新的 `TaskState`。
- 对下一步有什么影响：会影响后续阶段切换、步骤推进、恢复路径判断和活跃引用治理。

### 15.3 写回 `RetrievalState`
- 当前步骤在做什么：构造 `retrievalPlan`，合并激活查询、检索意图、证据引用和重排摘要，然后保存到 `retrievalStateStore`。
- 为什么要这么做：下一轮上下文编译必须知道上一轮检索过什么、命中过哪些证据、当前检索策略是什么。
- 输入是什么：旧检索状态、重构结果、重排结果、RAG/Memory/MCP 查询、检索补丁。
- 输出是什么：新的 `RetrievalState`。
- 对下一步有什么影响：影响后续检索去重、检索计划延续和恢复时的刷新判断。

### 15.4 写回 `ToolState`
- 当前步骤在做什么：解析最新工具原始引用、原始结果 JSON、摘要摘要、历史引用列表，并保存到 `toolStateStore`。
- 为什么要这么做：后续回放、恢复和工具证据治理都依赖最新工具状态。
- 输入是什么：旧工具状态、原始工具结果通道、工具语义、重构结果、工具历史引用。
- 输出是什么：新的 `ToolState`。
- 对下一步有什么影响：下一轮如果还需要复用工具证据，可以直接从这里读取。

### 15.5 写回 `ContextState` 与活跃引用
- 当前步骤在做什么：从 `summaryResult`、`rerankResult`、工具引用和 MCP 候选中提取活跃知识引用、记忆引用、工具证据引用、MCP Prompt/Resource/Workflow/Tool 引用，经过 `governActiveRefs(...)` 治理后，构造并保存新的 `ContextState`。
- 为什么要这么做：上下文状态不仅要保存叙事摘要和快照，还要保存哪些引用在下一轮仍然活跃。
- 输入是什么：旧 `ContextState`、摘要、重排结果、工具引用、阶段变化信息。
- 输出是什么：新的 `ContextState`。
- 对下一步有什么影响：下一轮 `contextCompilerService.compile(...)` 会以它作为上下文连续性的核心来源。

## 16. ChatServiceImpl.chat 收口阶段

### 16.1 提取正式轮次关键结果
- 当前步骤在做什么：从 `roundPipelineResult` 中提取 `modelResult`、可能更新后的 `toolSemanticResult`、`finalSnapshotId`、`summaryResult`。
- 为什么要这么做：正式轮次已经结束，服务层需要拿到最终回复、摘要和快照做最后收口。
- 输入是什么：`roundPipelineResult`。
- 输出是什么：正式轮次核心结果。
- 对下一步有什么影响：接下来会做最终可用性校验。

### 16.2 校验正式轮次是否成功
- 当前步骤在做什么：若 `roundPipelineResult == null`、或其被阻断、或 `modelResult == null`、或 `modelResult.isBlocked()`，则发布 `IDLE` 并返回 `503`，错误体为 `contextGovernanceBlockedPayload("chat turn aborted because final governed workset is empty")`。
- 为什么要这么做：即使正式轮次已经执行过，也不代表一定产出了可返回给用户的有效回复。
- 输入是什么：`roundPipelineResult`、`modelResult`。
- 输出是什么：失败响应或继续向下。
- 对下一步有什么影响：只有通过这里，才会进入日志覆盖、记忆写入和最终返回。

### 16.3 覆盖日志响应内容
- 当前步骤在做什么：调用 `LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(modelResult.getRawResponse())`。
- 为什么要这么做：保证日志切面记录的是主模型真实原始输出，而不是中间态或后处理内容。
- 输入是什么：`modelResult.getRawResponse()`。
- 输出是什么：线程级日志响应覆盖值。
- 对下一步有什么影响：本轮日志审计会对齐实际模型输出。

### 16.4 评估正式回复记忆写入闸门
- 当前步骤在做什么：调用 `evaluateMemoryWriteGate(input, modelResult.getReplyText(), reconstruction, toolSemanticResult, false)`；正式轮次阈值为 `0.45`。
- 为什么要这么做：不是每一轮回复都值得沉淀到记忆，必须用输入长度、回复长度、意图置信度、工具语义置信度综合打分。
- 输入是什么：用户输入、正式回复、输入重构结果、工具语义、`pendingTurn=false`。
- 输出是什么：`MemoryWriteGateDecision`。
- 对下一步有什么影响：决定是否执行正式轮次记忆写入。

### 16.5 条件执行正式轮次记忆写入
- 当前步骤在做什么：若 `writeGate.allowWrite()` 为真，则调用 `memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, modelResult.getReplyText(), contextPackage)`。
- 为什么要这么做：把本轮用户输入和正式回复沉淀到后续可复用的记忆层。
- 输入是什么：会话、用户输入、正式回复、结构化上下文。
- 输出是什么：记忆写入结果。
- 对下一步有什么影响：下轮上下文编译可能命中本轮沉淀的信息。

### 16.6 持久化回放比较与记忆治理审计
- 当前步骤在做什么：调用 `persistReplayAndMemoryGovernance(...)`，计算 `toolConfidence`、`summaryConfidence`、`intentConfidence`、`qualityScore` 以及前后快照是否可比，然后写入 `QUALITY_REPLAY_COMPARISON` 和 `MEMORY_WRITE_THRESHOLD_GOVERNANCE` 审计。
- 为什么要这么做：不仅要写记忆，还要记录本轮质量分数、前后快照关系和记忆治理阈值依据。
- 输入是什么：会话、计划/节点 ID、`previousContextState`、`finalSnapshotId`、`summaryResult`、`toolSemanticResult`、`reconstruction`。
- 输出是什么：两类治理审计记录。
- 对下一步有什么影响：为后续质量分析、快照对比和记忆治理优化提供依据。

### 16.7 发布空闲状态
- 当前步骤在做什么：调用 `statusPublisher.publish(DEFAULT_CLIENT_ID, STATUS_IDLE, VALUE_IDLE)`。
- 为什么要这么做：当前轮次所有同步处理已经完成，需要通知前端退出处理态。
- 输入是什么：状态常量。
- 输出是什么：空闲状态事件。
- 对下一步有什么影响：前端会把本轮 UI 状态切回空闲。

### 16.8 返回最终响应
- 当前步骤在做什么：调用 `tryParseJsonNode(modelResult.getValidResponse())`，并把结果包装为 `ResponseEntity.ok(...)` 返回。
- 为什么要这么做：主模型输出可能是标准 JSON，也可能只是普通字符串；这里统一做一次解析兼容。
- 输入是什么：`modelResult.getValidResponse()`。
- 输出是什么：最终 HTTP 响应体。
- 对下一步有什么影响：至此，本轮 `ChatController.message` 主链路结束；下一轮只能基于本轮已经写回的任务状态、检索状态、工具状态、上下文状态、快照和记忆继续推进。
