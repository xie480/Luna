# 接口名称
`ChatController.chat`

# 接口路径与方法
`POST /luna/api/chat/message`

# 接口职责
接收一轮用户输入，完成输入治理、会话状态推进、上下文编排、节点级检索与能力候选筛选、工具决策与执行、主模型回复生成、轮次状态写回、记忆写入与审计落库，并返回本轮回复结果。

# 请求参数
请求体类型：`org.yilena.luna.entity.ChatRequest`

字段：

`userInput`
用户输入原文。

字段级校验：

`ChatServiceImpl.chat` 仅校验 `chatRequest.userInput`。
校验方式是 `Optional.ofNullable(chatRequest).map(ChatRequest::getUserInput).map(String::trim).orElse("")`。
裁剪空白后为空时直接返回 `400 Bad Request`，响应体为字符串 `empty input`。
代码中没有其他字段，也没有使用 Bean Validation 注解。

# 返回结果
成功场景返回 `ResponseEntity<Object>`，实际响应体有 3 种来源：

正常完成主模型回复：
返回 `modelResult.getValidResponse()` 解析后的 `JsonNode`；如果解析失败则返回原始字符串。

工具异步挂起：
返回 `pendingReply` 解析后的 `JsonNode`；内容固定包含 `emotion`、`reply`、`status=pending`，以及在可解析时带上 `taskId`、`workflowName`。

上下文治理阻断：
返回 `503 Service Unavailable`，响应体为：
`{"status":"context_governance_blocked","message":"..."}`。

异常响应：

未捕获异常由 `GlobalExceptionHandler.handleException` 统一处理。
`NeedApprovalException` 被转换为 `200 OK`，响应体包含 `status=pending_approval`、`message`、`taskId`。
其他异常被转换为 `500 Internal Server Error`，响应体来自 `ExceptionRetryService.handleException(context)`。

# 核心业务流程
## 步骤 1：Controller 接收请求并进入服务层
做了什么：
`ChatController.chat(@RequestBody ChatRequest chatRequest)` 直接调用 `chatService.chat(chatRequest)`。

为什么要做：
控制层只负责 HTTP 协议适配，把请求体交给服务层主链路处理。

输入是什么：
HTTP 请求体反序列化后的 `ChatRequest`。

输出是什么：
`ResponseEntity<Object>`。

失败会怎样：
控制器本身没有捕获异常，异常继续向上抛给全局异常处理器。

对后续流程的影响：
流程立即进入 `ChatServiceImpl.chat`；控制层没有插入任何额外分支。

## 步骤 2：校验 `userInput` 并发布前端思考状态
做了什么：
`ChatServiceImpl.chat` 提取 `chatRequest.userInput`，执行 `trim`；为空时返回 `400`。校验通过后调用 `statusPublisher.publish("default", STATUS_THINKING, VALUE_THINKING)`。

为什么要做：
避免空输入继续进入上下文编排，同时让 SSE 订阅端看到当前进入思考阶段。

输入是什么：
`ChatRequest.userInput`。

输出是什么：
规范化后的 `input` 字符串，以及一条 SSE 状态消息 `THINKING / Luna 正在思考...`。

失败会怎样：
`input` 为空时直接返回 `"empty input"`，不再进入任何编排流程。

对后续流程的影响：
只有非空输入才会继续生成运行时会话 ID 并进入预工具上下文流水线。

## 步骤 3：解析运行时会话 ID
做了什么：
从 `AuthContextHolder.getSessionId()` 读取当前线程中的 JWT `jti`；非空则使用它。为空时使用 `DateTimeFormatter.ofPattern("yyyy:MM:dd")` 格式化当前时间作为会话 ID。

为什么要做：
为后续所有状态存储、检索、审计、工具回调缓存和记忆写入提供统一的 `sessionId`。

输入是什么：
线程上下文中的 `sessionId`，来源于 `AuthInterceptor.preHandle` 写入的 JWT `jti`；或者当前时间。

输出是什么：
`runtimeSessionId`。

失败会怎样：
这一步没有显式异常分支；如果鉴权没有写入线程变量，会自动回落到日期格式字符串。

对后续流程的影响：
后续 `stateDrivenContextPipeline`、状态仓库、Redis 热层、事件入口、审计日志、记忆落库全部使用这个 `runtimeSessionId`。

## 步骤 4：进入预工具状态驱动上下文流水线
做了什么：
先发布 `RETRIEVING` 状态，然后调用 `stateDrivenContextPipeline.run`，传入 `triggerSource=CHAT_PRE_TOOL`，内部 `RoundPipelineRequest` 的关键开关为：
`stage=CHAT_PRE_TOOL`
`repairSeed=input`
`runMainModel=false`
`assistantReplyOverride=""`
`replaceHistoryWithSummary=false`
`writeRoundState=false`

为什么要做：
在真正做工具决策前，先补齐当前轮所需的决策结果、结构化上下文、输入重构结果、节点工作集。

输入是什么：
`runtimeSessionId`
`input`
预工具阶段控制参数。

输出是什么：
`RoundPipelineResult preToolPipelineResult`。

失败会怎样：
`preToolPipelineResult == null` 或 `preToolPipelineResult.isBlocked()` 时，发布 `IDLE`，返回 `503`，消息为 `chat pre-tool pipeline blocked`。

对后续流程的影响：
只有预工具流水线产出完整治理结果时，后续才能进入工具决策和主模型阶段。

## 步骤 5：StateDrivenContextPipeline 充水请求并补齐上游治理产物
做了什么：
`StateDrivenContextPipelineImpl.run` 先校验 `StateDrivenContextPipelineRequest` 非空，然后执行 `hydrateRoundRequest`。
`hydrateRoundRequest` 在 `decision/contextPackage/reconstructionResult` 缺失且 `userInput` 非空时，调用 `taskOrchestratorService.orchestrateUserInput(sessionId, userInput)`。
如果 `nodeWorksetResult` 缺失且前三者完整，则继续调用 `taskOrchestratorService.orchestrateNodeWorkset(...)`。
随后把知识片段、偏好片段、长期记忆、工作记忆、运行时消息、检索记忆、执行候选、MCP 提示、`ContextNodeTemplatePolicy` 等重新组装成完整 `RoundPipelineRequest`。

为什么要做：
把控制层传来的“半成品轮次请求”补齐为真正可执行的编排输入。

输入是什么：
`StateDrivenContextPipelineRequest.roundPipelineRequest`
其中只有 `sessionId`、`userInput`、阶段控制开关是显式传入的。

输出是什么：
充水后的 `RoundPipelineRequest`。

失败会怎样：
`request` 或 `request.roundPipelineRequest` 为空时返回阻断结果 `state_driven_context_pipeline_request_missing`。
`hydrateRoundRequest` 返回空时返回阻断结果 `state_driven_context_pipeline_hydration_failed`。
如果输入重构结果不存在或 `explicitTaskGoal` 缺失，记录 `STATE_DRIVEN_PIPELINE_BLOCKED` 后中断。

对后续流程的影响：
只有在这一步补齐 `decision/contextPackage/reconstruction/nodeWorkset` 后，`RoundPipelineOrchestrator.executeRound` 才能继续运行。

## 步骤 6：`orchestrateUserInput` 构建当前会话决策结果、结构化上下文、用户输入重构实体
做了什么：
`TaskOrchestratorServiceImpl.orchestrateUserInput` 先调用 `contextCompilerService.compile(sessionId, userInput, null, null)` 构建预编译上下文。
接着调用 `inputReconstructionAgent.reconstruct(...)` 生成 `InputReconstructionResult`。
然后把 `GovernedSignal` 以 JSON 形式传给 `eventIngressService.ingestUserInput(sessionId, userInput, toJsonSafe(governedSignal))`。
事件入口会写入 `event_inbox`，去重后同步调用 `SessionOrchestratorService.onUserInput`。
`DefaultSessionOrchestratorService.onUserInput` 会读取当前任务状态、关系状态、会话类型，推导下一状态，写回 `agent_session` 与状态迁移日志，然后再次调用 `contextCompilerService.compile(sessionId, signal, nextTaskState, nextRelationalState)` 生成与新状态一致的 `StructuredContextPackage`。
最后 `orchestrateUserInput` 落审计：
`persistContextSnapshot`
`ORCHESTRATION_DECISION`
`INPUT_RECONSTRUCTION`
并返回 `TaskOrchestrationResult`。

为什么要做：
这是整条链路的上下文编排入口，用来把原始用户输入变成可用于检索、工具决策和主模型生成的标准化运行态。

输入是什么：
`sessionId`
`userInput`
上下文编译器从 DB/状态槽/Redis 读取到的历史运行态。

输出是什么：
`TaskOrchestrationResult`
其中包含：
`OrchestrationDecision decision`
`StructuredContextPackage contextPackage`
`InputReconstructionResult reconstructionResult`
以及恢复分支标记。

失败会怎样：
`eventIngressService.ingestUserInput` 要求 `orchestration_signal` 非空且可解析；不满足时记录 `EVENT_GOVERNED_SIGNAL_INVALID` 并返回空。
恢复代理和上下文编译内部异常大多被局部吞掉并退化为空结构；真正未捕获异常会继续上抛。

对后续流程的影响：
这一步产出的三个核心实体是后续节点工作集、工具决策、主模型提示词组装、状态写回的共同输入。

## 步骤 7：上下文编译器加载运行实体、状态快照、缓存、预加载策略、记忆与 Token 预算
做了什么：
`DefaultContextCompilerService.compile` 的真实顺序是：
先判断 `isCacheEligible(options)`，仅在 `ContextCompileOptions` 为 `AUTO` 且未显式配置 fallback 时允许命中编译上下文缓存。
若允许缓存，则从 `MemoryHotLayerService.getCompiledContextCache(sessionId, userInput, taskState, relationalState)` 读取 Redis 热缓存；命中则直接返回。
未命中时调用 `runtimeRetriever.retrieve(sessionId)`。
`JdbcRuntimeRetriever.retrieve` 先查 Redis 会话热缓存；未命中再从 DB 加载：
`agent_session` 运行行
最近消息
活动工具结果
上下文快照
再补上 Redis 中的 `pending_tool_call`，最后回写 Redis 会话缓存。
随后编译器从状态槽加载：
`TaskStateStore.load`
`RetrievalStateStore.load`
`ToolStateStore.load`
`ContextStateStore.load`
`RecoveryStateStore.load`
再执行 `resolvePreloadDecision(taskState, options)`：
显式 `FULL` 时预加载任务记忆和关系记忆
显式 `MINIMAL` 时只保留最小运行态
`AUTO` 模式下，仅当 `fallbackPreloadEnabled=true` 且任务态为 `EXECUTING/WAITING_TOOL/WAITING_APPROVAL` 时才自动全预加载，否则默认最小预加载。
任务记忆预加载调用 `JdbcTaskMemoryRetriever.retrieve`：
优先命中 Redis 工作记忆缓存；未命中时从 DB 加载 `working_memory`、`working_slots`、`plan_context`，并回写 Redis。
之后总是加载 `task_perceptual_buffer`、`task_episode_steps`。
仅当任务态复杂、查询语义命中关键词、或近端工作记忆不足时，才计算 embedding 并查询 `task_facts`、`task_episodes`、`task_procedures`、`knowledge`。
关系记忆预加载调用 `JdbcRelationalMemoryRetriever.retrieve`：
加载 `working_memory`、`profile`、`emotional_baseline`、`boundary_rules`、`relational_perceptual_buffer`。
仅在关系态敏感、语义查询命中偏好/边界类关键词、或近端关系上下文不足时，才计算 embedding 并查询 `semantic_facts`、`episodes`、`procedures`。
之后编译器调用 `socialReasonerService.buildRelationalDraft`、`responseSynthesizerService.buildSynthesisPolicy`，构建 `promptPolicy`。
再通过 `buildTokenBudget` 生成 `tokenBudgetPlan`：
情绪支持/脆弱时给 `relational_working`、`relational_episodes`、`recent_messages` 更高预算；
普通场景给 `task_working`、`plan_node`、`knowledge` 更高预算。
最后汇总为 `StructuredContextPackage` 并在允许缓存时写回 Redis 编译上下文缓存，TTL 为 3 分钟。

为什么要做：
把当前轮真实可用的运行态、状态快照、任务记忆、关系记忆、预加载策略、提示词策略和预算方案一次性组装出来，供下游统一消费。

输入是什么：
`sessionId`
`userInput`
任务态/关系态
Redis 热层
DB 运行时表
状态槽表
记忆检索表。

输出是什么：
`StructuredContextPackage`，其中至少包含：
`runtime`
`taskContext`
`relationalContext`
`promptPolicy`
`tokenBudgetPlan`
`taskStateEntity`
`retrievalState`
`toolState`
`contextState`
`recoveryState`。

失败会怎样：
Redis 和 DB 读取失败大多被局部捕获并退化为空 `Map` 或空 `List`。
编译上下文缓存反序列化失败会当作未命中处理。

对后续流程的影响：
这一步决定了后续输入重构、RAG/MCP 查询重写、Prompt 组装、Token 预算裁剪、恢复刷新和状态回放可用的信息边界。

## 步骤 8：输入重构代理重写用户输入，生成 RAG 查询、MCP 查询与澄清槽位
做了什么：
`DefaultInputReconstructionAgent.reconstruct` 先从 `StructuredContextPackage` 中抽取信号：
目标
当前节点
待确认问题
上次工具
工具语义摘要
检索意图
最新叙事摘要
最近对话
最近时间范围
下一步提示
状态快照摘要。
然后优先走模型路径 `tryModelReconstruction`：
向轻量模型请求一个 JSON，要求返回：
`normalizedUserIntent`
`explicitTaskGoal`
`clarifiedEntities`
`missingSlots`
`timeScope`
`businessConstraints`
`reformulatedQueryForRag`
`reformulatedQueryForMcp`
`blueprintHint`
`intentConfidence`
如果模型返回为空或 `explicitTaskGoal` 为空，则退化到启发式路径：
从输入和上下文里抽取季度、日期、数字、`working_goal`、`active_node`、`latest_tool`
推断 `explicitTaskGoal`
推断 `timeScope`
推断 `businessConstraints`
推断 `missingSlots`
构造 `normalizedUserIntent`
生成 `buildRagQuery` 与 `buildMcpQuery`。

为什么要做：
把原始自然语言转成检索和编排可直接消费的结构化输入。

输入是什么：
`sessionId`
原始 `userInput`
当前 `StructuredContextPackage`
当前任务态和关系态。

输出是什么：
`InputReconstructionResult`。

失败会怎样：
模型路径异常时只写 debug 日志，然后回退到启发式重构。
若最终 `explicitTaskGoal` 仍不可用，会在 `hydrateRoundRequest` 阶段阻断流水线。

对后续流程的影响：
`explicitTaskGoal`、`reformulatedQueryForRag`、`reformulatedQueryForMcp`、`missingSlots` 会直接影响节点工作集、状态机阻断、工具参数生成和主模型提示词输入。

## 步骤 9：节点工作集流水线执行 RAG 查询重写、MCP 查询重写、检索、全局重排和执行候选筛选
做了什么：
`TaskOrchestratorServiceImpl.orchestrateNodeWorkset` 先执行 `evaluateReconstructionRecallGate`，检查：
是否有 `explicitTaskGoal`
`intentConfidence` 是否达到当前任务态阈值
缺失槽位数量是否超限
实体数量是否满足要求。
通过后生成 `worksetTraceId`，再构建 `mcpDrivenInput = mcpQueryBuilder.build(...)`。
如果 `mcpDrivenInput` 为空则记录 `NODE_WORKSET_BLOCKED` 并阻断。
然后调用：
`capabilityPolicyRouterService.routeForContext(...)` 获取 MCP 原始候选
`mcpCandidatePreRank.preRank(...)` 做系统级预排序
并把结果记录为 `MCP_PRE_RANK`。
接着构建：
`ragQuery = ragQueryBuilder.build(...)`
`memoryQuery = memoryQueryBuilder.build(...)`
任一为空则记录 `NODE_WORKSET_BLOCKED` 并阻断。
之后调用 `retrievalService.retrieve` 两次：
一次检索 `KNOWLEDGE + PREFERENCE`
一次检索 `MEMORY`
再用 `mergeRetrievalResponses` 合并。
合并后的候选进入 `globalContextRerankAgent.rerank(...)`，生成 `ContextRerankResult`，并记录：
`MULTI_ROUTE_RECALL_TRACE`
`RERANK_TRACE_BOTTOM_CHANNELS`
`GLOBAL_CONTEXT_RERANK`
随后从重排结果中提取：
`selectedKnowledgeEvidenceBlocks`
`selectedKnowledgeSnippets`
`selectedMemorySnippets`
`selectedPreferenceSnippets`
`executionCandidates`
`mcpResourceHints`
以及被选中的 prompt/resource/workflow/tool 名称集合。

为什么要做：
这一阶段负责把“可检索上下文”和“可执行能力”收敛成当前节点真正能使用的工作集。

输入是什么：
`sessionId`
`userInput`
`OrchestrationDecision`
`StructuredContextPackage`
`InputReconstructionResult`。

输出是什么：
`NodeWorksetResult`。

失败会怎样：
重构召回门未通过时直接阻断。
`mcpDrivenInput`、`ragQuery`、`memoryQuery` 构建失败时直接阻断。
召回或重排抛异常时，记录 `NODE_ORCHESTRATION_RECALL_FAILED`，但退化为知识、记忆、偏好均为空，继续返回一个空工作集结果。

对后续流程的影响：
`NodeWorksetResult` 决定后续工具候选、RAG 证据块、MCP 提示、检索查询落库内容以及主模型看到的证据边界。

## 步骤 10：预工具轮次执行 `RoundPipelineOrchestrator.executeRound`
做了什么：
`RoundPipelineOrchestratorImpl.executeRound` 收到充水后的 `RoundPipelineRequest`。
因为预工具阶段 `runMainModel=false`，所以不会调用主模型，但仍会执行两件事：
如果 `request.toolSemanticResult` 为空，则调用 `resolveToolSemantic(...)` 对当前 `toolContext` 和执行候选做一次工具语义补齐。
调用 `taskOrchestratorService.orchestrateSummary(...)` 生成 `SummaryResult`，但因为 `writeRoundState=false`，不会写回状态仓库。
`StateDrivenContextPipelineImpl.run` 最后把结果重新封装为 `RoundPipelineResult` 返回。

为什么要做：
即使预工具阶段不跑主模型，也要先形成统一的语义补齐和摘要产物，保证后续工具决策与正式轮次的输入结构一致。

输入是什么：
充水后的 `RoundPipelineRequest`。

输出是什么：
`RoundPipelineResult`，其中 `decision/contextPackage/reconstruction/nodeWorkset` 由充水请求回填，`summaryResult/toolSemanticResult` 由执行器补齐。

失败会怎样：
`request` 为空时返回阻断结果 `round_request_missing`。
如果内部语义翻译异常，会退化为 `fallbackToolSemanticResult`，而不是阻断整个轮次。

对后续流程的影响：
`ChatServiceImpl` 从这个结果中正式取出 `decision/contextPackage/reconstruction/nodeWorkset`，进入真正的工具决策阶段。

## 步骤 11：提取上下文片段并构造节点级模板策略
做了什么：
`ChatServiceImpl.chat` 从 `contextPackage` 和 `nodeWorkset` 中抽取：
任务知识片段
关系偏好片段
长期记忆片段
工作记忆片段
运行时消息片段
RAG 记忆片段
知识证据块
执行候选
MCP 资源提示。
同时调用 `resolveNodeTemplatePolicy(decision, contextPackage)`，必要时再通过 `SessionRuntimeMapper.selectNodeTypeByPlanAndNode(planId, nodeId)` 获取当前节点类型。

为什么要做：
为后面的工具决策上下文和主模型上下文提供节点粒度的片段池与模板边界。

输入是什么：
`StructuredContextPackage`
`NodeWorksetResult`
`OrchestrationDecision`。

输出是什么：
一组片段列表和 `ContextNodeTemplatePolicy`。

失败会怎样：
片段提取和节点类型查询内部异常大多被吞掉并退化为空字符串或空列表。

对后续流程的影响：
这些片段会进入工具决策上下文组装、主模型上下文组装、状态回写和记忆写入决策。

## 步骤 12：工具决策节点组装上下文、校验治理签名、选择目标能力并执行
做了什么：
`TaskOrchestratorServiceImpl.orchestrateToolDecisionNode` 先把当前节点片段、证据块、候选资源和 `ContextNodeTemplatePolicy.forToolDecision(...)` 交给 `contextAssembler.assemble(...)`，生成 `assembledDecisionContext`。
然后把以下数据写入 `ToolCallingContextHolder`：
`chatSessionKey`
`userInput`
`toolDecisionInput=mcpDrivenInput`
`governedInputSignature`
`assembledDecisionContext`
各类 memory/knowledge/preference/longTerm 片段
`executionCandidates`
`mcpResourceHints`
空的 `toolExecutionTraces` 列表。
接着用 `contextSnapshotStore.savePreToolDecisionSnapshot(...)` 保存工具决策前快照，再用 `saveToolDecisionContextSnapshot(...)` 保存组装后的工具决策上下文快照，并记录 `CONTEXT_SNAPSHOT_PRE_TOOL`。
真正的工具选择与执行由 `agentService.processToolCallingWithGovernance(...)` 完成，执行顺序是：
`resolveStableSessionId`
`validateGovernedDecisionContext` 校验 `assembledDecisionContext`、`toolDecisionInput`、`governedInputSignature`
若 `capabilityPolicyRouterService.shouldTriggerPlanOrchestration(decisionInput, taskState)` 为真，直接切到 `planOrchestratorService.createAndRunPlan(...)`
否则拿 `executionCandidates`；为空时才调用 `toolRouter.findCandidates(...)`
调用 `llmAdapter.generate(buildDecisionPrompt(...))` 生成决策 JSON
`parseDecisionAction` 解析 `action_type/target_name/arguments`
`direct_answer` 直接返回文本
否则 `resolveTarget(candidates, decision)` 将模型给出的名字映射到真实 `Resource`
参数为空时调用 `llmAdapter.generate(buildArgsPrompt(...))`
若参数不匹配 `resource.inputSchema`，再调用修复 Prompt 重新生成参数
`executionGate.check(target)` 做权限闸门
按 `ResourceType` 分流：
`WORKFLOW` 走 `workflowExecutor.execute`
`PROMPT` 走 `mcpService.getPrompt`
`RESOURCE` 走 `mcpService.readResource`
`STRATEGY` 且需要计划编排时走 `planOrchestratorService.createAndRunPlan`
普通 `TOOL` 走 `toolExecutionGateway.executeTool`
其中实际网关是 `McpToolExecutionGateway.executeTool`，会：
校验 `resource.type == TOOL`
校验 `argsJson` 符合输入 schema
再次执行 `executionGate.check`
若 `needApproval(resource)` 成立则调用 `approvalService.createTaskAndInterrupt`
否则调用 `mcpClientAdapter.callTool(serverCode, name, arguments)` 并规范化为 `ExecutionResult`。
无论哪条执行分支，`AgentServiceImpl` 都会把执行轨迹写入 `ToolCallingContextHolder`。
`orchestrateToolDecisionNode` 在 finally 中调用 `persistToolExecutionTraces(...)` 把轨迹写入 `runtime_tool_execution_trace`，再调用 `eventIngressService.ingestToolResult` 推进事件状态机。
最后再执行 `resolveToolSemanticFromRequest(...)`，把原始工具输出翻译成 `ToolSemanticResult`，并调用 `persistImmediateToolSemanticState(...)` 立即写回 `ToolStateStore` 和 `ContextStateStore`。

为什么要做：
这一阶段负责把节点工作集转换成真实工具动作，并把工具输出转成主模型可消费的结构化语义。

输入是什么：
`sessionId`
`userInput`
`OrchestrationDecision`
`StructuredContextPackage`
`InputReconstructionResult`
`NodeWorksetResult`。

输出是什么：
`ToolDecisionNodeResult`，包含：
`toolContext`
`rawToolResultChannel`
`toolTraceRefs`
`toolSemantic`
`preToolSnapshotId`
`toolDecisionSnapshotId`。

失败会怎样：
治理校验失败时直接拒绝进入工具决策，记录 `UNGOVERNED_TOOL_DECISION_REJECTED`。
模型选不到目标资源时返回空。
工具参数 schema 校验失败时尝试 repair prompt。
网关若需要审批会抛 `NeedApprovalException`，最终由全局异常处理器改写为 `pending_approval`。
普通工具调用异常会记录失败轨迹后继续向上抛。

对后续流程的影响：
`toolContext`、`rawToolResultChannel`、`ToolSemanticResult`、工具轨迹引用和快照 ID 会直接进入异步挂起判断、主模型上下文组装、状态写回和审计。

## 步骤 13：生成三阶段综合摘要并把工具语义合并回工具上下文
做了什么：
`ChatServiceImpl.chat` 调用 `threeStageResponseService.generateSynthesisBrief(input, toolContext, contextPackage)`。
`DefaultThreeStageResponseService.generateSynthesisBrief` 会串行执行三次模型调用：
任务草稿
关系草稿
融合草稿
模板来源于 `contextPackage.promptPolicy.response_synthesis`。
随后 `ChatServiceImpl` 调用：
`mergeToolContextWithSemantic(toolContext, toolSemanticResult)`
`mergeToolContextWithSynthesis(semanticToolContext, synthesisBrief)`
把工具语义状态、下一步提示、业务影响、语义 payload、三阶段摘要一起合入 `mergedToolContext`。
同时记录 `RESPONSE_SYNTHESIS` 审计。

为什么要做：
把原始工具输出进一步压缩为主模型更容易消费的结构化事实和融合摘要。

输入是什么：
`input`
`toolContext`
`toolSemanticResult`
`contextPackage`。

输出是什么：
`mergedToolContext`。

失败会怎样：
三阶段摘要内部任何异常都被 `DefaultThreeStageResponseService` 捕获并返回空字符串；不会阻断主链路。

对后续流程的影响：
后续异步挂起判断和主模型上下文都会基于这个合并后的 `mergedToolContext`。

## 步骤 14：判断工具是否处于异步挂起，并在挂起时提前返回
做了什么：
`ChatServiceImpl.isAsyncPending(mergedToolContext)` 会把 `mergedToolContext` 解析成 JSON，并检查 `status == "pending"`。
命中后会：
`buildPendingReply(mergedToolContext)` 生成固定挂起回复
`cachePendingToolCall(runtimeSessionId, mergedToolContext)` 把 `taskId/workflowName/status/toolContext` 写入 `MemoryHotLayerService.putPendingToolCall`，落到 Redis，TTL 2 小时
调用 `evaluateMemoryWriteGate(..., pendingTurn=true)` 计算记忆写入分数
分数通过时执行 `memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, pendingReply, contextPackage)`
再次调用 `stateDrivenContextPipeline.run`，阶段为 `CHAT_TURN_PENDING`，其中：
`runMainModel=false`
`assistantReplyOverride=pendingReply`
`writeRoundState=true`
`retrievalPlanOverrides.pending=true`
`retrievalPlanOverrides.nextActionHint=await_tool_callback`
`retrievalPlanOverrides.pendingRecoveryAnchor=toolDecisionSnapshotId`
最后发布 `IDLE` 并返回挂起 JSON。

为什么要做：
工具异步执行时，接口不等待回调，而是先把挂起态、恢复锚点和待回调缓存持久化，立即响应前端。

输入是什么：
`mergedToolContext`
`toolDecisionSnapshotId`
工具轨迹引用
上下文片段。

输出是什么：
挂起回复 JSON、Redis 中的 pending tool call、挂起轮次状态。

失败会怎样：
挂起缓存写 Redis、记忆写入、挂起轮次流水线内部异常多为局部吞掉；真正未捕获异常仍会进入全局异常处理。

对后续流程的影响：
一旦命中挂起分支，正式主模型回复不会执行；流程在这里结束，等待后续工具回调事件重新推进。

## 步骤 15：进入正式轮次流水线并运行主模型
做了什么：
非挂起时先发布 `THINKING / VALUE_THINKING_ORGANIZE`，然后构造 `RoundPipelineRequest`，关键参数为：
`stage=CHAT_TURN`
`runMainModel=true`
`replaceHistoryWithSummary=true`
`writeRoundState=true`
携带当前轮的：
`decision`
`contextPackage`
`reconstructionResult`
`nodeWorksetResult`
`toolSemanticResult`
各类 memory/knowledge/preference 片段
`executionCandidates`
`mcpResourceHints`
`nodeTemplatePolicy`
`toolContext=mergedToolContext`
工具原始结果通道。
然后再次调用 `stateDrivenContextPipeline.run(triggerSource=CHAT_TURN)`。
正式轮次中的 `RoundPipelineOrchestrator.executeRound` 顺序是：
如无现成工具语义则补做 `resolveToolSemantic`
执行预摘要 `taskOrchestratorService.orchestrateSummary(..., replaceHistory=false, triggerSource=PRE_ASSEMBLY_INPUT)`
调用 `taskOrchestratorService.orchestrateMainModel(...)`
再执行后摘要 `orchestrateSummary(..., replaceHistory=true, triggerSource=CHAT_TURN)`
因为 `writeRoundState=true`，再执行 `writeRoundState(...)`。
`orchestrateMainModel` 会：
先构建 `activeRefs`
解析主模型 Prompt 策略 `resolveMainModelPromptAssembly`
调用 `contextAssembler.assembleAndSnapshot(...)`
在 `DefaultContextAssembler` 中按固定章节组装：
`Instructions`
`Current Task State`
`Reconstructed User Intent`
`Relevant Knowledge Evidence`
`MCP Resource / Prompt Hints`
`Tool Evidence`
`Recent Interaction Context`
`Memory Hints`
`Output Constraints`
然后把这些章节交给 `SemanticPreservingPruner.prune(...)`，预算来自 `contextPackage.tokenBudgetPlan` 与 `ContextNodeTemplatePolicy.sectionBudgetOverrides`。
若发现语义一致性问题，会把 `semantic_consistency_guard` 补进 `Output Constraints` 后再次裁剪。
之后生成最终 `prompt`，保存 `FINAL_MODEL_CONTEXT` 快照，记录 `CONTEXT_SNAPSHOT_FINAL`。
若最终 `prompt` 为空，则记录 `CONTEXT_GOVERNANCE_BLOCKED` 并阻断。
若 `prompt` 存在，则调用 `invokeMainModel(...)`：
选模型 `resolveExecutionModelName(contextPackage)`，优先 `gemini.code`、再按关系态选 `gemini.chat`、否则 `gemini.big/flash`
发起主模型调用，要求结果是包含 `reply` 的 JSON
若返回为空，直接构造 fallback JSON `{"thought":"fallback","emotion":"Solemn","reply":"Generation failed, please retry."}`
若返回不是合法 reply JSON，则再走一次 repair prompt：
`resolveRepairPromptResult`
`persistRepairSnapshotRefs`
`resolveRepairPromptTemplate`
再次调用模型生成修复后的 JSON
修复仍失败时再回退到 fallback JSON
最后把 `thought` 字段从 `validResponse` 中移除，只保留干净 JSON，并抽取 `replyText`。

为什么要做：
这一轮才真正生成用户可见的正式回复，并把上下文、证据、工具输出、提示词治理和快照统一落地。

输入是什么：
预工具阶段产出的全部治理结果、工具语义、工具上下文、节点工作集、Token 预算和 Prompt 策略。

输出是什么：
`RoundPipelineResult`
其中核心是：
`MainModelOrchestrationResult`
`SummaryResult`
`finalSnapshotId`
更新后的 `ToolSemanticResult`。

失败会怎样：
上下文组装为空会阻断并返回 `503`。
主模型返回空或格式非法时先尝试 repair，再 fallback 到固定 JSON。
主模型阶段本身不抛格式异常给上层，而是内部修复或降级。

对后续流程的影响：
这一阶段决定最终返回给前端的回复内容，同时为轮次状态写回、历史压缩、记忆写入、质量回放比较提供最终产物。

## 步骤 16：写回任务状态、检索状态、工具状态、上下文状态
做了什么：
`TaskOrchestratorServiceImpl.writeRoundState` 从 `RoundStateWriteRequest` 和旧状态快照构建四类状态：
`TaskState`
合并 `confirmedSlots`
合并 `pendingQuestions`
统计 `finishedSteps/failedSteps`
计算 `retryCount`
把 `objective/currentStage/currentNode/nextActionHint` 写回 `TaskStateStore`
`RetrievalState`
合并 `ragQuery/memoryQuery/mcpQuery`
合并 `reconstruction.reformulatedQueryForRag/reformulatedQueryForMcp`
写入 `retrievalPlan`
记录 `selectedEvidenceRefs/rerankSummary`
写回 `RetrievalStateStore`
`ToolState`
计算 `latestToolRawRef`
解析 `latestToolRawResultJson`
计算 `sha256` 摘要和 preview
保存 `lastToolName/lastToolInput/lastToolStatus/lastToolSemanticSummary/toolCallHistoryRefs`
写回 `ToolStateStore`
`ContextState`
根据 `summaryResult.stateSnapshot` 和活跃引用治理结果生成：
`latestNarrativeSummary`
`latestStateSnapshot`
`activeKnowledgeRefs`
`activeMemoryRefs`
`activeToolEvidenceRefs`
`activeMcpPromptRefs`
`activeMcpResourceRefs`
`activeMcpWorkflowRefs`
`activeMcpToolRefs`
`latestContextSnapshotId`
写回 `ContextStateStore`。

为什么要做：
让下一轮能够基于稳定、结构化、可回放的状态快照继续运行。

输入是什么：
`RoundStateWriteRequest`
旧的 `TaskState/RetrievalState/ToolState/ContextState`
本轮 `reconstruction/rerankResult/toolSemanticResult/summaryResult`
工具轨迹引用和检索计划覆盖项。

输出是什么：
四类状态仓库中的最新 JSON 状态槽。

失败会怎样：
各状态仓库基于 `AbstractJsonStateStore.saveState` 做 upsert，内部异常被吞掉，不主动中断主流程。

对后续流程的影响：
下一轮上下文编译、恢复刷新、历史压缩、工具回放、检索重用都依赖这里写下的状态快照。

## 步骤 17：执行历史摘要替换、记忆写入门控、记忆流水线与质量回放治理
做了什么：
`ChatServiceImpl.chat` 从正式轮次结果取出 `modelResult.replyText`、`summaryResult`、`finalSnapshotId`。
先调用 `evaluateMemoryWriteGate(..., pendingTurn=false)`：
分数计算公式为：
`inputSignal*0.30 + replySignal*0.25 + intentSignal*0.25 + semanticSignal*0.20`
正式轮阈值为 `0.45`
若 `intentConfidence >= 0.60` 或 `toolSemantic.confidence >= 0.70` 也可直接放行。
结果记录为 `MEMORY_WRITE_GATE`。
放行时调用 `memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, replyText, contextPackage)`。
`DefaultMemoryWritePipelineService.writeAfterTurn` 的真实顺序是：
插入 USER/ASSISTANT 消息
更新会话任务态和关系态
更新任务工作记忆 `upsertTaskWorkingMemory`
更新关系工作记忆 `upsertRelationalWorkingMemory`
构建 `MemoryWritePolicyGate.GateContext`
抽取并写入语义事实
写入长期关系记忆
生成 episode
挖掘 procedure
更新 procedure 统计
刷新 working memory registry。
之后 `persistReplayAndMemoryGovernance(...)` 会记录：
`QUALITY_REPLAY_COMPARISON`
`MEMORY_WRITE_THRESHOLD_GOVERNANCE`
其中质量分 = `toolConfidence*0.50 + summaryConfidence*0.25 + intentConfidence*0.25`。
同时，`orchestrateSummary(..., replaceHistory=true)` 已经在更早一步执行历史摘要替换逻辑：
只有 `replaceHistory=true`
且 `recentMessages.size >= 8` 或存在 `stateSnapshot`
且 `narrativeSummary` 非空时，才调用 `sessionService.replaceHistoryWithSummary(sessionId, narrativeSummary, snapshotText)`。
否则写 `HISTORY_REPLACEMENT_SKIPPED`。

为什么要做：
把本轮结果沉淀为可复用记忆，同时避免低质量、不稳定或中间态信息过早进入长期记忆。

输入是什么：
本轮 `input`
正式 `replyText`
`StructuredContextPackage`
`InputReconstructionResult`
`ToolSemanticResult`
`SummaryResult`。

输出是什么：
消息表、工作记忆、长期记忆、episode、procedure、历史摘要替换结果、质量治理审计。

失败会怎样：
记忆流水线内部大多数 DB 写入异常都被局部吞掉并继续。
长期记忆候选若命中 `PENDING_TOOL_RESULT/INTERMEDIATE_INFERENCE/UNVERIFIED_CONCLUSION` 或当前任务态处于等待工具/审批/用户，会被硬拒绝，不写入长期记忆。

对后续流程的影响：
这些结果会影响下一轮上下文编译能否命中近端工作记忆、长期事实、关系记忆和历史摘要。

## 步骤 18：发布空闲状态并返回最终响应
做了什么：
`ChatServiceImpl.chat` 发布 `IDLE / ""`，然后把 `modelResult.getValidResponse()` 交给 `tryParseJsonNode` 解析；解析成功返回 `JsonNode`，解析失败返回原始字符串。

为什么要做：
通知前端本轮链路结束，并把最终模型 JSON 返回给调用方。

输入是什么：
`MainModelOrchestrationResult.validResponse`。

输出是什么：
`200 OK` 的最终回复对象。

失败会怎样：
如果在正式轮次之前已经被阻断，则不会走到这里。

对后续流程的影响：
HTTP 链路结束，下一轮只能基于已写回的状态、快照、缓存和记忆继续运行。

# 分支逻辑说明
输入为空：
直接返回 `400 empty input`。

预工具流水线阻断：
返回 `503`，消息可能是 `chat pre-tool pipeline blocked` 或 `chat pre-tool pipeline artifacts missing`。

输入重构缺失 `explicitTaskGoal`：
在 `hydrateRoundRequest` 阶段被阻断，不进入节点工作集和主模型。

节点工作集阻断：
`mcp_query_not_buildable`
`rag_query_not_buildable`
`memory_query_not_buildable`
或重构召回门未通过时阻断。

工具决策无候选或无目标：
`AgentServiceImpl.processToolCallingWithGovernance` 返回 `null`，上游继续走主模型，但 `toolContext` 为空。

工具异步挂起：
命中 `toolContext.status == pending` 时提前返回，不进入正式主模型回复。

需要人工审批：
`McpToolExecutionGateway` 中 `approvalService.createTaskAndInterrupt` 会触发 `NeedApprovalException`，最终由全局异常处理器返回 `pending_approval`。

正式轮次上下文治理阻断：
主模型最终 prompt 为空时返回 `503`，消息为 `chat turn aborted because final governed workset is empty`。

主模型返回非法 JSON：
先走 repair prompt；修复失败后回退到固定 fallback JSON。

记忆写入门控未通过：
只跳过 `memoryWritePipelineService.writeAfterTurn`，不会影响本轮响应返回。

# 状态流转说明
接口级前端状态流转：

进入接口后发布 `THINKING`。
进入预工具上下文流水线前发布 `RETRIEVING`。
非挂起时进入正式主模型前发布 `THINKING / VALUE_THINKING_ORGANIZE`。
挂起返回或正常返回前都发布 `IDLE`。

任务状态流转来源：
真实任务态由 `DefaultSessionOrchestratorService.inferTaskState(...)` 推导，不由 `ChatServiceImpl` 直接决定。

常见用户输入驱动状态：

无历史或历史为 `IDLE/COMPLETED/CANCELLED` 时，结构化输入默认落到 `UNDERSTANDING`。
命中计划关键词时可进入 `PLANNING`。
命中执行关键词时可进入 `EXECUTING`。
工具回调 `status=pending` 时进入 `WAITING_TOOL`。
审批等待时进入 `WAITING_APPROVAL`。
计划确认等待时进入 `WAITING_PLAN_CONFIRMATION`。
失败或错误信号会进入 `REFLECTING/REPLANNING/FAILED`。
完成类信号会进入 `COMPLETED/REPORTING`。

关系状态流转来源：
由 `inferRelationalState(...)` 基于结构化 signal、工具错误、审批拒绝和情绪/边界信号推导，可能进入：
`FAMILIARIZING`
`TRUST_BUILDING`
`COMPANION_MODE`
`LIGHT_CHAT`
`EMOTIONAL_SUPPORT`
`REPAIRING`
`CELEBRATING`
`DEEP_TALK`。

状态持久化方式：

会话主状态写入 `agent_session`，由 `SessionRuntimeMapper` 完成。
任务/检索/工具/上下文状态写入状态槽表，键分别为：
`state.task`
`state.retrieval`
`state.tool`
`state.context`
写入方法为 `AbstractJsonStateStore.saveState -> StateStoreMapper.upsertStateSlot(...)`。

工具挂起状态持久化方式：

Redis 热层保存 `pending_tool_call`。
正式状态机通过 `retrievalPlanOverrides.pending=true`、`nextActionHint=await_tool_callback` 和 `pendingRecoveryAnchor=toolDecisionSnapshotId` 落到轮次状态中。

# 上下文与记忆机制
当前会话决策结果：`OrchestrationDecision`

构建来源：
`SessionOrchestratorService.onUserInput` 在事件入口中根据旧任务态、旧关系态、会话类型、结构化 signal、执行快照推导后创建。

字段组成：
`sessionId`
`taskState`
`relationalState`
`contextPackage`

生命周期：
当前轮在 `orchestrateUserInput` 中创建。
正式状态写回后，其核心状态被拆分沉淀到 `agent_session` 与状态槽。
下一轮会重新生成新的 `OrchestrationDecision`。

结构化上下文：`StructuredContextPackage`

构建来源：
`DefaultContextCompilerService.compile`。

字段组成：
`sessionId`
`taskState`
`relationalState`
`runtime`
`taskContext`
`relationalContext`
`recentMessages`
`capabilityCandidates`
`promptPolicy`
`tokenBudgetPlan`
`taskStateEntity`
`retrievalState`
`toolState`
`contextState`
`recoveryState`

生命周期：
在上下文编译器中创建。
短期可命中 Redis 编译缓存，TTL 3 分钟。
正式轮次结束后，其对应状态被再次写回状态仓库和审计快照。

用户输入重构实体：`InputReconstructionResult`

构建来源：
`DefaultInputReconstructionAgent.reconstruct`，优先模型重构，失败后启发式重构。

字段组成：
`normalizedUserIntent`
`explicitTaskGoal`
`clarifiedEntities`
`missingSlots`
`timeScope`
`businessConstraints`
`reformulatedQueryForRag`
`reformulatedQueryForMcp`
`blueprintHint`
`intentConfidence`

生命周期：
在 `orchestrateUserInput` 中创建。
同轮内被 `hydrateRoundRequest`、`orchestrateNodeWorkset`、`orchestrateToolDecisionNode`、`orchestrateMainModel`、`writeRoundState`、记忆写入门控复用。
最终其关键字段会被写入 `TaskState` 和 `RetrievalState`。

缓存机制：

Redis 会话热缓存：
`JdbcRuntimeRetriever` 优先命中；命中后跳过 DB 读取 `agent_session/recent_messages/active_tool_results/context_snapshots`。

Redis 工作记忆热缓存：
`JdbcTaskMemoryRetriever` 优先命中；命中后跳过 DB 读取 `working_memory/working_slots/plan_context`。

Redis 编译上下文缓存：
`DefaultContextCompilerService` 在 `AUTO` 模式下允许命中；缓存键由 `sessionId + fingerprint(userInput, taskState, relationalState)` 组成。

Redis 挂起工具缓存：
`MemoryHotLayerService.putPendingToolCall` 按 `sessionId + taskId` 和 `sessionId latest` 两份索引保存。

首次进入流水线判定：

代码中没有单独的 `isFirstPipeline` 布尔值。
首次进入会话的行为由“状态缺失时的默认值”体现：
`getCurrentTaskState` 缺失时回退为 `IDLE`
`getCurrentRelationalState` 缺失时回退为 `COLD_START`
`getCurrentSessionType` 缺失时由 `SessionType.from` 决定默认值
之后 `inferTaskState` 会把首轮结构化输入推进到 `UNDERSTANDING`，`inferRelationalState` 会把首轮推进到 `FAMILIARIZING` 或 `LIGHT_CHAT`。

上下文摘要机制：

预工具阶段与正式轮次都会调用 `orchestrateSummary`。
正式轮次在 `replaceHistory=true` 且达到统一阈值时，会用摘要替换长历史。

记忆加载机制：

任务记忆：
`working_memory`
`working_slots`
`plan_context`
`task_perceptual_buffer`
`task_episode_steps`
按条件触发的 `task_facts/task_episodes/task_procedures/knowledge`

关系记忆：
`working_memory`
`profile`
`emotional_baseline`
`boundary_rules`
`relational_perceptual_buffer`
按条件触发的 `semantic_facts/episodes/procedures`

Query Planning / 查询重写：

存在。
`InputReconstructionResult.reformulatedQueryForRag`
`InputReconstructionResult.reformulatedQueryForMcp`
`NodeWorksetResult.ragQuery`
`NodeWorksetResult.memoryQuery`
`NodeWorksetResult.mcpDrivenInput`
都是同一轮的真实查询重写结果。

Token 预算策略：

存在。
`DefaultContextCompilerService.buildTokenBudget` 先生成原始预算。
`DefaultContextAssembler.sectionBudget` 再映射到提示词章节预算。
`SemanticPreservingPruner.prune` 按预算裁剪章节，并输出 `sectionTokenCounts`、`sectionTokenRatios`。

工具调用状态恢复机制：

存在。
工具轨迹写入 `runtime_tool_execution_trace`。
`ToolState.lastToolRawResultRef`、`toolCallHistoryRefs`、`ContextState.activeToolEvidenceRefs` 保存可回放引用。
挂起工具通过 Redis `pending_tool_call` 和 `retrievalPlanOverrides.pendingRecoveryAnchor` 保存恢复锚点。

# 异常处理
`ChatServiceImpl.chat` 自身没有总括 `try-catch`，只在若干分支上显式返回失败响应。

显式返回型异常处理：

输入为空返回 `400 empty input`。
预工具流水线阻断返回 `503 context_governance_blocked`。
正式轮次治理阻断返回 `503 context_governance_blocked`。

下游局部降级点：

`DefaultInputReconstructionAgent.tryModelReconstruction` 失败后回退到启发式重构。
`DefaultThreeStageResponseService.generateSynthesisBrief` 任一阶段失败返回空字符串。
`orchestrateNodeWorkset` 的召回/重排异常记录 `NODE_ORCHESTRATION_RECALL_FAILED`，退化为空工作集。
`RoundPipelineOrchestrator.resolveToolSemantic` 翻译失败时回退为 `fallbackToolSemanticResult`。
`invokeMainModel` 主模型返回非法 JSON 时先 repair，再 fallback JSON。
`AbstractJsonStateStore`、各 JDBC Retriever、MemoryWritePipeline 内部大量数据库读写失败都退化为空结果或忽略，不阻断主流程。

全局兜底：

`NeedApprovalException` 转为 `200 OK` 的 `pending_approval` 响应。
`AuthException` 转为 `401`。
其他异常由 `GlobalExceptionHandler` 收集请求体、URI、方法、参数后，交给 `ExceptionRetryService.handleException` 生成 `500` 响应。

# 依赖模块
Controller：
`ChatController`

主服务：
`ChatServiceImpl`

状态驱动流水线：
`StateDrivenContextPipelineImpl`
`RoundPipelineOrchestratorImpl`

任务编排：
`TaskOrchestratorServiceImpl`

上下文编译与组装：
`DefaultContextCompilerService`
`DefaultContextAssembler`

输入治理：
`DefaultInputReconstructionAgent`

事件入口与状态机：
`DefaultEventIngressService`
`DefaultSessionOrchestratorService`

检索与重排：
`RagQueryBuilder`
`MemoryQueryBuilder`
`McpQueryBuilder`
`RetrievalService`
`GlobalContextRerankAgent`
`McpCandidatePreRank`
`McpResourceHintExtractor`

工具决策与执行：
`AgentServiceImpl`
`ToolRouter`
`McpToolExecutionGateway`
`WorkflowExecutor`
`ApprovalService`

摘要与回复：
`SummaryAgent`
`DefaultThreeStageResponseService`
`LlmClientUtil`

状态持久化：
`TaskStateStoreImpl`
`RetrievalStateStoreImpl`
`ToolStateStoreImpl`
`ContextStateStoreImpl`
`ContextSnapshotStoreImpl`

热层与缓存：
`DefaultMemoryHotLayerService`

审计：
`JdbcRuntimeAuditService`

记忆写入：
`DefaultMemoryWritePipelineService`

鉴权与会话上下文：
`AuthInterceptor`
`AuthContextHolder`

# 在 Agent 系统中的位置
`ChatController.chat` 是普通聊天入口，但它不是“只聊天”的薄接口，而是整个 Agent 运行时的同步主入口。

在链路中的位置顺序是：

HTTP 入口
输入治理入口
事件状态机入口
结构化上下文编译入口
节点级检索与能力编排入口
工具决策与执行入口
主模型生成入口
轮次状态写回入口
记忆写入入口
审计与回放入口

它在 Agent 系统中的职责边界是：

同步收敛当前轮所有可用上下文。
把用户输入推进到任务状态机和关系状态机。
在需要时调工具、调工作流、调 MCP。
把工具结果和上下文治理结果转成主模型可消费的最终提示词。
把本轮产物沉淀为状态、快照、记忆和审计记录。

它不是：

启动接口。
关闭接口。
单纯的 LLM 透传接口。
单独的 RAG 接口。

它是当前项目里“单轮对话请求驱动整个 Agent Runtime 前进一步”的主链路入口。
