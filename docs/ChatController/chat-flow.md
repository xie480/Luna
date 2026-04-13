# ChatController.message 主链路

## 1. `ChatController.chat`

### 1.1 接收 HTTP 请求并转交服务层
这一步在做什么：
`ChatController.chat(@RequestBody ChatRequest chatRequest)` 接收 `POST /luna/api/chat/message` 请求，把反序列化后的 `ChatRequest` 直接传给 `chatService.chat(chatRequest)`。

为什么要做这一步：
Controller 层只负责 HTTP 协议适配，不负责聊天编排、状态推进和模型调用。这样可以保证主业务逻辑集中在服务层，接口层保持足够薄。

这一阶段的数据或状态发生了什么变化：
HTTP 请求体被转换成 Java 对象 `ChatRequest`，调用链从 Web 层进入服务层。

对后续流程有什么影响：
从这里开始，真正的主链路切换到 `ChatServiceImpl.chat`，后续所有状态治理、工具决策、模型生成和持久化都在服务层完成。

## 2. `ChatServiceImpl.chat`

### 2.1 提取并校验 `userInput`
这一步在做什么：
方法先通过 `Optional.ofNullable(chatRequest).map(ChatRequest::getUserInput).map(String::trim).orElse("")` 提取输入文本，得到规范化后的 `input`。如果 `input` 为空，直接返回 `400 Bad Request`，响应体是 `empty input`。

为什么要做这一步：
空输入不具备继续执行上下文编译、检索、工具决策和模型生成的意义，越早拦截越能避免无效计算和脏状态写入。

这一阶段的数据或状态发生了什么变化：
原始请求中的用户文本被规整成可执行的 `input` 字符串；无效请求会在这里终止。

对后续流程有什么影响：
只有非空输入才会继续进入状态发布、会话 ID 解析、预工具流水线和正式回复生成流程。

### 2.2 发布前端状态并确定 `runtimeSessionId`
这一步在做什么：
服务先通过 `statusPublisher.publish(...)` 发布 `THINKING` 状态。随后优先从 `AuthContextHolder.getSessionId()` 读取当前会话 ID；如果取不到，再使用 `yyyy:MM:dd` 格式的当前时间生成 `runtimeSessionId`。

为什么要做这一步：
前端需要明确知道当前请求已经进入处理中；后续整条链路又必须依赖一个统一的会话标识，把上下文、状态、审计、工具缓存和记忆落到同一条会话上。

这一阶段的数据或状态发生了什么变化：
前端观察到接口从空闲进入思考中；服务端确定了本轮统一使用的 `runtimeSessionId`。

对后续流程有什么影响：
后续 `stateDrivenContextPipeline.run`、状态仓库、Redis 热层、审计日志、挂起工具缓存和记忆写入都会使用这个 `runtimeSessionId`。

### 2.3 启动预工具状态驱动流水线
这一步在做什么：
服务发布 `RETRIEVING` 状态，然后调用 `stateDrivenContextPipeline.run(...)`。传入的 `RoundPipelineRequest` 处于 `stage=CHAT_PRE_TOOL`，并明确设置：

`runMainModel=false`  
`assistantReplyOverride=""`  
`replaceHistoryWithSummary=false`  
`writeRoundState=false`

为什么要做这一步：
在真正判断“是否需要调工具”之前，系统要先把本轮的决策信息、结构化上下文、输入重构结果和节点工作集补齐。这个阶段的目的不是直接回复用户，而是为后面的真实执行做准备。

这一阶段的数据或状态发生了什么变化：
请求进入一个“只做治理、不做最终回复生成”的预处理轮次。

对后续流程有什么影响：
如果预工具流水线被阻断，后续工具决策和正式主模型阶段都不会执行，接口会直接返回 `503`。

## 3. `StateDrivenContextPipelineImpl.run`（预工具阶段）

### 3.1 校验状态驱动请求
这一步在做什么：
`run` 方法先检查 `StateDrivenContextPipelineRequest` 以及内部的 `roundPipelineRequest` 是否为空。缺失时直接返回阻断结果 `state_driven_context_pipeline_request_missing`。

为什么要做这一步：
状态驱动流水线必须以轮次请求为基础才能执行。连基础请求都没有，就无法安全进入后续水化和编排逻辑。

这一阶段的数据或状态发生了什么变化：
如果请求缺失，流水线立即进入阻断状态，不再继续。

对后续流程有什么影响：
只有请求合法时，才会进入 `hydrateRoundRequest` 进行真正的上下文补齐。

### 3.2 水化轮次请求
这一步在做什么：
`run` 调用 `hydrateRoundRequest(request)`，把一个只带有基础字段的轮次请求补齐成可执行的完整请求。

为什么要做这一步：
进入轮次编排器之前，至少要准备好：

`OrchestrationDecision`  
`StructuredContextPackage`  
`InputReconstructionResult`  
`NodeWorksetResult`

否则后面的工具语义、摘要、工具决策和主模型生成都没有足够输入。

这一阶段的数据或状态发生了什么变化：
“半成品请求”会被扩展成一个带完整治理工件、片段和候选资源的 `RoundPipelineRequest`。

对后续流程有什么影响：
`RoundPipelineOrchestrator.executeRound` 接收到的，将不再是原始输入，而是一个具备业务语义和上下文边界的完整轮次请求。

### 3.3 记录预工具流水线审计上下文
这一步在做什么：
水化完成后，流水线会基于 `sessionId`、`triggerSource`、`planId`、`nodeId` 等信息生成追踪标识，并在执行前记录多段审计钩子，标记当前已经具备哪些治理产物、当前阶段是什么、是否需要运行主模型等。

为什么要做这一步：
这条链路很长，且会经历多个阶段。提前记录执行上下文，能让后续排查知道当前请求在哪个阶段拿到了哪些关键工件。

这一阶段的数据或状态发生了什么变化：
不会改变业务结果，但会生成更完整的链路追踪信息。

对后续流程有什么影响：
后续无论成功、阻断还是降级，都可以通过这些审计记录还原本轮在预工具阶段的真实状态。

### 3.4 调用轮次编排器执行预工具轮次
这一步在做什么：
流水线把水化后的 `RoundPipelineRequest` 交给 `roundPipelineOrchestrator.executeRound(...)` 执行。

为什么要做这一步：
状态驱动流水线本身负责“补齐请求和组织执行”，真正的轮次执行仍然由轮次编排器完成。

这一阶段的数据或状态发生了什么变化：
预工具阶段开始真正运行轮次逻辑，包括工具语义补齐和摘要生成。

对后续流程有什么影响：
轮次编排器的执行结果，会成为 `ChatServiceImpl.chat` 后续工具决策和正式主模型阶段的直接输入。

### 3.5 对结果做兜底封装并返回
这一步在做什么：
`run` 在拿到 `RoundPipelineResult` 后，会做结果判空、阻断检查，并在必要时用水化请求中的字段兜底补齐返回对象里的 `decision`、`contextPackage`、`reconstructionResult`、`nodeWorksetResult`。

为什么要做这一步：
预工具轮次可能只补齐了一部分数据，但下游 `ChatServiceImpl.chat` 需要尽量完整的结果对象来继续执行。

这一阶段的数据或状态发生了什么变化：
返回的 `RoundPipelineResult` 会尽量保证关键字段完整，而不是把空值直接抛给下游。

对后续流程有什么影响：
`ChatServiceImpl.chat` 能稳定地从预工具结果中提取治理产物，减少因为单个字段缺失导致的额外失败。

## 4. `StateDrivenContextPipelineImpl.hydrateRoundRequest`

### 4.1 读取基础输入并识别缺失工件
这一步在做什么：
`hydrateRoundRequest` 从外层请求和内层 `RoundPipelineRequest` 中提取 `sessionId`、`userInput`、`decision`、`contextPackage`、`reconstructionResult`、`nodeWorksetResult`，判断当前缺哪些核心工件。

为什么要做这一步：
水化不是盲目重建所有数据，而是先识别当前请求已经具备什么、还缺什么，只补真正缺失的部分。

这一阶段的数据或状态发生了什么变化：
系统明确知道当前轮次请求是“完整请求”还是“半成品请求”。

对后续流程有什么影响：
后面的编排动作将根据缺失情况选择性执行，而不是重复做所有工作。

### 4.2 缺失核心治理结果时调用 `orchestrateUserInput`
这一步在做什么：
当 `decision`、`contextPackage`、`reconstructionResult` 任意缺失且 `userInput` 非空时，调用 `taskOrchestratorService.orchestrateUserInput(sessionId, userInput)`，回填三项核心结果。

为什么要做这一步：
这三项是后续整个链路最基础的输入，没有它们，节点工作集生成、工具决策和主模型执行都无法继续。

这一阶段的数据或状态发生了什么变化：
当前轮次第一次拿到会话决策、结构化上下文和输入重构结果。

对后续流程有什么影响：
只有这三项齐备，后面才可能继续做 `NodeWorksetResult` 的生成和轮次执行。

### 4.3 校验输入重构是否达到可执行标准
这一步在做什么：
`hydrateRoundRequest` 会检查 `reconstructionResult` 是否存在，且 `explicitTaskGoal` 是否非空。如果不满足，会记录 `STATE_DRIVEN_PIPELINE_BLOCKED` 相关审计信息，并返回 `null`，表示水化失败。

为什么要做这一步：
没有明确任务目标，就说明系统还不知道“这一轮到底要完成什么”，继续做节点工作集和主模型生成只会扩大噪音。

这一阶段的数据或状态发生了什么变化：
不满足条件时，请求被标记为治理阻断，请求不会再继续向下执行。

对后续流程有什么影响：
一旦这里失败，预工具流水线会终止，`ChatServiceImpl.chat` 会返回 `503`。

### 4.4 缺失节点工作集时调用 `orchestrateNodeWorkset`
这一步在做什么：
当 `nodeWorksetResult` 缺失，且 `decision/contextPackage/reconstructionResult` 已经齐备时，调用 `taskOrchestratorService.orchestrateNodeWorkset(...)` 生成本轮节点级工作集。

为什么要做这一步：
上下文包只是“全量可用上下文”，节点工作集才是“本轮真正选中的上下文和能力候选”。正式进入工具决策前，必须完成这一步收敛。

这一阶段的数据或状态发生了什么变化：
系统得到精简后的知识证据块、记忆片段、偏好片段、执行候选和 MCP 资源提示。

对后续流程有什么影响：
后面的工具决策节点和主模型上下文组装，都会优先使用节点工作集，而不是直接使用全量上下文。

### 4.5 合并片段、候选和节点模板策略，生成最终水化请求
这一步在做什么：
水化逻辑会从请求本身和 `nodeWorksetResult` 中合并知识片段、偏好片段、长期记忆、工作记忆、运行时消息、检索记忆、执行候选、MCP 资源提示，并组装进新的 `RoundPipelineRequest`。

为什么要做这一步：
轮次编排器需要的是一份可以直接执行的统一输入，而不是零散分布在多个对象里的原始字段。

这一阶段的数据或状态发生了什么变化：
最终产出的 `RoundPipelineRequest` 具备执行轮次所需的上下文片段、候选资源和治理结果。

对后续流程有什么影响：
预工具轮次和正式轮次都会直接消费这份水化后的请求。

## 5. `TaskOrchestratorServiceImpl.orchestrateUserInput`

### 5.1 先编译一次上下文，构造重构和状态机所需的事实底座
这一步在做什么：
`orchestrateUserInput` 会先调用 `contextCompilerService.compile(sessionId, userInput, null, null)`，拿到一版基于当前会话现状的结构化上下文。

为什么要做这一步：
输入重构不能脱离历史状态、近期消息、任务进度和关系上下文单独进行。先编译上下文，才能让重构建立在事实之上。

这一阶段的数据或状态发生了什么变化：
当前会话的运行态、状态快照、记忆和提示策略被汇总成一版初始 `StructuredContextPackage`。

对后续流程有什么影响：
后面的输入重构和状态推进都要依赖这版上下文中的事实信息。

### 5.2 重构用户输入，生成 `InputReconstructionResult`
这一步在做什么：
方法调用 `inputReconstructionAgent.reconstruct(...)`，把原始用户输入转成结构化结果，包括任务目标、RAG/MCP 查询、缺失槽位、业务约束和意图置信度。

为什么要做这一步：
系统真正需要的是“明确目标”和“可检索、可决策的结构化查询”，而不是一段未经治理的自然语言。

这一阶段的数据或状态发生了什么变化：
原始输入被重写成更适合状态机、检索和工具决策消费的结构化对象。

对后续流程有什么影响：
状态机要基于这里产生的治理信号推进任务态和关系态，节点工作集也要基于这里的查询结果做召回。

### 5.3 把治理后的输入送入事件入口和状态机
这一步在做什么：
系统把治理后的信号交给 `eventIngressService.ingestUserInput(...)`。事件入口会写入事件表，再同步调用 `SessionOrchestratorService.onUserInput`，由状态机推导新的任务状态和关系状态，并写回 `agent_session`。

为什么要做这一步：
用户发来一条新消息，不只是“多了一句话”，还意味着当前任务进度和关系状态可能发生变化。系统必须让状态机感知这次输入。

这一阶段的数据或状态发生了什么变化：
任务状态、关系状态、会话级状态迁移日志等信息会被更新。

对后续流程有什么影响：
第二次上下文编译时，将不再基于旧状态，而是基于新推进后的状态生成更准确的上下文包。

### 5.4 基于新状态重新编译上下文并返回三大核心工件
这一步在做什么：
状态推进完成后，`orchestrateUserInput` 会再调用一次上下文编译器，得到与新状态一致的 `StructuredContextPackage`，最终返回：

`OrchestrationDecision`  
`StructuredContextPackage`  
`InputReconstructionResult`

为什么要做这一步：
第一次上下文编译是为了支撑输入重构，第二次编译才是真正面向当前轮后续执行的正式上下文。

这一阶段的数据或状态发生了什么变化：
系统得到了本轮可继续向下使用的三大核心治理工件。

对后续流程有什么影响：
水化过程和正式轮次后续步骤都会直接依赖这里返回的结果。

## 6. `TaskOrchestratorServiceImpl.orchestrateNodeWorkset`

### 6.1 校验是否满足召回和编排前提
这一步在做什么：
方法会先检查输入重构结果是否具备 `explicitTaskGoal`，意图置信度是否达标，缺失槽位是否超限，实体信息是否足够。

为什么要做这一步：
如果输入重构质量不足，继续做 MCP/RAG/记忆召回只会召回更多噪音，反而让后续决策不稳定。

这一阶段的数据或状态发生了什么变化：
不满足条件时，本轮节点工作集会直接阻断或降级。

对后续流程有什么影响：
只有通过召回门控，后面才会真正执行查询构建、能力召回和重排。

### 6.2 构建 MCP 查询、RAG 查询和记忆查询
这一步在做什么：
系统基于 `InputReconstructionResult` 和当前上下文构建：

`mcpDrivenInput`  
`ragQuery`  
`memoryQuery`

为什么要做这一步：
后面的能力候选召回、知识召回和记忆召回都不是直接使用原始用户输入，而是使用这里生成的“治理后的查询表达”。

这一阶段的数据或状态发生了什么变化：
同一轮请求首次得到可落库、可复用的查询重写结果。

对后续流程有什么影响：
这些查询既决定本轮召回什么，也会在状态写回时进入 `RetrievalState`，供下一轮参考。

### 6.3 执行能力候选召回、知识检索和全局重排
这一步在做什么：
系统会先召回 MCP 能力候选并做预排序，再调用检索服务获取知识、偏好和记忆候选，最后通过全局重排得到最终选中的证据块、片段和执行候选。

为什么要做这一步：
单纯召回只会得到一堆候选，真正要进入后续链路的必须是“当前最相关”的一小部分上下文和能力。

这一阶段的数据或状态发生了什么变化：
系统从大量候选中收敛出当前轮次真正要使用的：

知识证据块  
知识片段  
记忆片段  
偏好片段  
执行候选  
MCP 资源提示

对后续流程有什么影响：
工具决策节点和主模型最终 prompt 的信息边界，基本由这一步确定。

## 7. `RoundPipelineOrchestratorImpl.executeRound`（预工具轮次）

### 7.1 补齐工具语义结果
这一步在做什么：
如果请求里没有现成的 `toolSemanticResult`，轮次编排器会调用 `resolveToolSemantic(...)`，把当前工具上下文翻译成结构化业务语义。

为什么要做这一步：
即使预工具阶段还没有正式回答用户，后续链路也需要知道“当前工具结果在业务上代表什么、下一步该做什么”。

这一阶段的数据或状态发生了什么变化：
原始工具上下文被补充为结构化 `ToolSemanticResult`；如果翻译失败，会回退到 `fallbackToolSemanticResult`。

对后续流程有什么影响：
后续工具上下文合并、正式主模型回复、状态写回和记忆门控，都会依赖这个语义结果。

### 7.2 生成预工具阶段摘要
这一步在做什么：
由于预工具轮次 `runMainModel=false`，不会执行主模型，但仍会调用 `orchestrateSummary(...)` 生成一份摘要结果。

为什么要做这一步：
摘要可以把当前治理结果压缩成更便于后续消费的中间产物，也便于正式轮次时保持输入结构一致。

这一阶段的数据或状态发生了什么变化：
本轮预工具阶段生成了摘要结果，但不会在这一步写回轮次状态。

对后续流程有什么影响：
后续 `ChatServiceImpl.chat` 在进入工具决策和正式回复阶段时，会复用这里补齐的摘要和工具语义产物。

## 8. 回到 `ChatServiceImpl.chat`（预工具结果出栈后）

### 8.1 校验预工具结果是否完整
这一步在做什么：
服务检查 `preToolPipelineResult` 是否为空或被阻断，再检查 `decision`、`contextPackage`、`reconstruction`、`nodeWorkset` 是否齐全。任一不满足，直接发布 `IDLE` 并返回 `503`。

为什么要做这一步：
后面的工具决策和正式聊天轮次都要求这四项核心治理结果齐备。只要少一项，本轮都不应该继续执行。

这一阶段的数据或状态发生了什么变化：
不完整的请求会在这里被终止；完整的请求则会进入后续上下文片段抽取阶段。

对后续流程有什么影响：
通过校验后，后面才能安全执行工具决策和主模型生成。

### 8.2 提取上下文片段并解析节点模板策略
这一步在做什么：
服务从 `contextPackage` 与 `nodeWorkset` 中抽取知识片段、偏好片段、长期记忆片段、工作记忆片段、运行时消息片段、RAG 记忆片段、知识证据块、执行候选、MCP 资源提示，并解析 `ContextNodeTemplatePolicy`。

为什么要做这一步：
后续无论是工具决策还是正式主模型轮次，都需要的是按用途拆好的片段，而不是原始的大对象。

这一阶段的数据或状态发生了什么变化：
综合上下文被拆解成可直接进入后续组装的片段池；节点工作集中的精选知识和偏好还会覆盖默认提取结果。

对后续流程有什么影响：
工具决策节点和正式轮次的上下文组装，都依赖这些片段。

### 8.3 执行工具决策节点
这一步在做什么：
服务调用 `taskOrchestratorService.orchestrateToolDecisionNode(...)`，基于当前输入、决策、上下文包、输入重构和节点工作集，执行工具决策逻辑，拿到：

`toolContext`  
`toolSemanticResult`  
`rawToolResultChannel`  
`toolDecisionSnapshotId`  
`latestToolRawRef`  
`toolHistoryRefs`  
`rawToolExecutionTraces`

为什么要做这一步：
系统需要在正式回答用户前先判断：这一轮是不是需要调工具，如果调了工具，拿到了什么结果，这些结果又该如何解释。

这一阶段的数据或状态发生了什么变化：
本轮首次拿到真正的工具链产出，以及工具原始结果和执行痕迹。

对后续流程有什么影响：
后面的挂起分支判断和正式主模型上下文组装，都建立在这些结果之上。

### 8.4 合并工具上下文，生成 `mergedToolContext`
这一步在做什么：
服务先用 `threeStageResponseService.generateSynthesisBrief(...)` 生成综合摘要，再把 `toolSemanticResult` 融入 `toolContext`，最后再把综合摘要融入，形成 `mergedToolContext`，并记录 `RESPONSE_SYNTHESIS` 审计日志。

为什么要做这一步：
原始工具结果一般太底层，主模型和后续治理更需要“已经被解释过、被压缩过、带业务语义”的工具事实输入。

这一阶段的数据或状态发生了什么变化：
工具原始结果、工具语义和综合摘要被融合为统一的工具事实上下文。

对后续流程有什么影响：
后续不论是挂起回复，还是正式聊天轮次的主模型输入，都会基于 `mergedToolContext`。

## 9. `ChatServiceImpl.chat` 异步挂起分支

### 9.1 判断工具是否处于 `pending` 状态
这一步在做什么：
服务通过 `isAsyncPending(mergedToolContext)` 判断工具链是否返回 `status=pending`。

为什么要做这一步：
有些工具和工作流不会在当前请求内同步完成。如果仍然强行继续主模型生成，就会让系统基于未完成的结果输出错误回复。

这一阶段的数据或状态发生了什么变化：
链路会在这里分成两个分支：

异步挂起分支  
正常继续正式回复分支

对后续流程有什么影响：
命中挂起分支时，主模型不会在本次请求中执行。

### 9.2 构造挂起回复并缓存待恢复工具调用
这一步在做什么：
如果命中挂起，系统会生成 `pendingReply`，同时把待恢复工具调用写入 Redis 热层缓存。

为什么要做这一步：
前端需要一个可直接展示的“后台处理中”回复，系统也需要保存恢复锚点，以便工具回调回来后继续推进链路。

这一阶段的数据或状态发生了什么变化：
Redis 中会新增 `pending_tool_call`，保存当前挂起任务的 `taskId`、`workflowName`、`toolContext` 等信息。

对后续流程有什么影响：
后续工具回调或恢复流程可以基于这里保存的挂起信息继续执行。

### 9.3 执行挂起轮次状态写回并直接返回
这一步在做什么：
系统会先评估挂起轮次的记忆写入门控，再触发一次 `stage=CHAT_TURN_PENDING` 的状态驱动流水线，写入“等待工具回调”的轮次状态，最后发布 `IDLE` 并返回挂起响应。

为什么要做这一步：
即使本轮没有生成正式回复，也要把当前状态和恢复锚点落下来，保证系统对“已经进入后台执行”的事实有完整记录。

这一阶段的数据或状态发生了什么变化：
轮次状态中会写入：

`pending=true`  
`nextActionHint=await_tool_callback`  
`pendingRecoveryAnchor=toolDecisionSnapshotId`

对后续流程有什么影响：
本次请求到此结束，正式主模型不会执行；后续要等工具回调重新推进。

## 10. `ChatServiceImpl.chat` 正式聊天轮次分支

### 10.1 发布“整理中”状态并组装正式轮次请求
这一步在做什么：
如果没有挂起，服务会发布 `THINKING / VALUE_THINKING_ORGANIZE`，保存旧的 `ContextState`，然后构造正式 `RoundPipelineRequest`，设置：

`stage=CHAT_TURN`  
`runMainModel=true`  
`replaceHistoryWithSummary=true`  
`writeRoundState=true`

并把治理结果、节点工作集、工具语义、工具上下文、上下文片段、候选资源和工具原始结果通道全部带入。

为什么要做这一步：
到这里系统已经拿齐用户输入治理结果、节点工作集和工具结果，终于满足正式生成用户可见回复的全部前提。

这一阶段的数据或状态发生了什么变化：
请求从预工具轮次切换到正式聊天轮次，上下文中加入了完整的工具事实和状态写回配置。

对后续流程有什么影响：
后面进入的状态驱动流水线将真正执行主模型，并在结束后写回正式轮次状态。

### 10.2 再次调用 `stateDrivenContextPipeline.run`
这一步在做什么：
服务用新的正式轮次请求再次调用 `stateDrivenContextPipeline.run(...)`，触发完整的正式聊天轮次执行。

为什么要做这一步：
预工具轮次只负责治理和准备，正式轮次才负责真正的回复生成、摘要替换和状态落库。

这一阶段的数据或状态发生了什么变化：
正式轮次进入 `StateDrivenContextPipelineImpl.run` 和 `RoundPipelineOrchestratorImpl.executeRound` 的完整执行阶段。

对后续流程有什么影响：
本轮用户最终会看到的回复，就在这一阶段生成。

## 11. `RoundPipelineOrchestratorImpl.executeRound`（正式聊天轮次）

### 11.1 补齐工具语义
这一步在做什么：
如果正式轮次请求里还没有 `toolSemanticResult`，轮次编排器会再次调用 `resolveToolSemantic(...)` 补齐。

为什么要做这一步：
正式主模型阶段必须保证工具语义结果存在，否则模型只能看到原始工具输出，不利于稳定生成。

这一阶段的数据或状态发生了什么变化：
工具结果会被进一步翻译成结构化语义，必要时走回退语义结果。

对后续流程有什么影响：
主模型和摘要生成都会直接消费这份工具语义。

### 11.2 执行主模型前摘要
这一步在做什么：
正式轮次开始后，编排器会先调用 `orchestrateSummary(..., replaceHistory=false)`，把当前轮上下文压缩成更适合主模型消费的摘要输入。

为什么要做这一步：
上下文片段很多，直接全部塞给主模型会增加冗余和预算压力。预摘要相当于在生成前先做一次上下文整理。

这一阶段的数据或状态发生了什么变化：
生成了主模型前的摘要结果 `preAssemblySummary`。

对后续流程有什么影响：
后面的 `orchestrateMainModel(...)` 会把这份预摘要作为输入之一使用。

### 11.3 调用 `orchestrateMainModel` 生成正式回复
这一步在做什么：
编排器把上下文包、输入重构结果、知识证据块、记忆片段、偏好片段、长期记忆、执行候选、MCP 资源提示、工具上下文、节点模板策略、预摘要和工具原始结果通道等信息，一起组装进 `MainModelExecutionRequest`，交给 `taskOrchestratorService.orchestrateMainModel(...)`。

为什么要做这一步：
这一轮才是真正把所有治理结果汇总起来，让主模型基于完整事实边界生成用户可见的正式回复。

这一阶段的数据或状态发生了什么变化：
系统生成 `MainModelOrchestrationResult`，里面包含：

`validResponse`  
`replyText`  
`rawResponse`  
`finalSnapshotId`

对后续流程有什么影响：
这份结果会直接决定接口返回内容，也会成为后摘要、状态写回和记忆写入的基础输入。

### 11.4 校验主模型执行结果
这一步在做什么：
如果 `modelResult` 为空或被阻断，轮次编排器会立刻返回阻断结果，而不会继续执行后摘要和状态写回。

为什么要做这一步：
一旦主模型阶段失败，继续落摘要和状态会掩盖真实失败原因，还可能把不完整结果写入状态仓库。

这一阶段的数据或状态发生了什么变化：
失败时轮次进入阻断状态；成功时继续向下执行。

对后续流程有什么影响：
只有主模型结果有效，后面才会继续做正式摘要和正式轮次状态持久化。

### 11.5 执行主模型后摘要
这一步在做什么：
主模型成功后，编排器会调用 `orchestrateSummary(..., replaceHistory=true)` 生成本轮正式摘要。

为什么要做这一步：
正式摘要既服务于本轮的状态写回，也服务于后续的历史替换和下一轮上下文压缩。

这一阶段的数据或状态发生了什么变化：
系统得到 `summaryResult`，其中可能包含叙事摘要、状态快照等信息。

对后续流程有什么影响：
后续 `writeRoundState` 和记忆治理都会使用这份正式摘要。

### 11.6 写回任务、检索、工具和上下文状态
这一步在做什么：
由于正式轮次 `writeRoundState=true`，编排器会调用 `writeRoundState(...)`，把本轮的输入重构、检索查询、工具语义、摘要结果、快照 ID、工具引用和检索计划覆盖项写入状态仓库。

为什么要做这一步：
下一轮对话必须基于本轮沉淀下来的结构化状态继续运行，不能每轮都只依赖即时上下文。

这一阶段的数据或状态发生了什么变化：
以下状态会被更新：

`TaskState`  
`RetrievalState`  
`ToolState`  
`ContextState`

对后续流程有什么影响：
下一轮上下文编译、恢复机制、工具回放和检索重用，都依赖这里写回的状态。

## 12. 回到 `ChatServiceImpl.chat`（正式轮次结果出栈后）

### 12.1 校验正式轮次是否成功完成
这一步在做什么：
服务从 `roundPipelineResult` 中提取 `modelResult`、`toolSemanticResult`、`finalSnapshotId`、`summaryResult`，然后校验结果是否为空、是否被阻断、主模型结果是否存在且可用。不满足条件时直接发布 `IDLE` 并返回 `503`。

为什么要做这一步：
就算正式轮次已经执行过，也不代表最终一定得到了可返回用户的有效结果。这里要做最后一道收口校验。

这一阶段的数据或状态发生了什么变化：
如果通过校验，会把 `modelResult.getRawResponse()` 写入 `LunaLogAspect.LOG_RESPONSE_OVERRIDE`；如果失败，则本轮直接结束。

对后续流程有什么影响：
只有通过这里，才会继续进入记忆写入和最终响应返回阶段。

### 12.2 评估记忆写入门控
这一步在做什么：
服务调用 `evaluateMemoryWriteGate(...)`，基于输入长度、回复长度、意图置信度、工具语义置信度计算分数，决定本轮是否允许记忆写入。

为什么要做这一步：
不是每轮都值得进入长期记忆或工作记忆。系统需要过滤掉信息量低、质量不稳定或仍处于中间态的结果。

这一阶段的数据或状态发生了什么变化：
本轮得到一个明确的门控结果：

是否允许写入  
门控分数  
允许或拒绝原因

对后续流程有什么影响：
记忆门控通过，才会继续执行记忆写入流水线；否则只保留响应，不沉淀记忆。

### 12.3 执行记忆写入和回放治理持久化
这一步在做什么：
如果门控通过，系统调用 `memoryWritePipelineService.writeAfterTurn(...)`，把用户消息、助手回复、工作记忆、关系记忆、长期记忆候选、episode 和 procedure 写入持久层。之后再调用 `persistReplayAndMemoryGovernance(...)`，记录质量对比和记忆治理审计。

为什么要做这一步：
系统要把本轮真正有价值的结果沉淀成后续可以复用的状态资产，同时保留审计依据，说明为什么这轮被允许或不允许进入记忆。

这一阶段的数据或状态发生了什么变化：
消息表、工作记忆、长期记忆、关系记忆和治理审计数据会被更新。

对后续流程有什么影响：
下一轮上下文编译是否能命中本轮沉淀的消息、事实、偏好和任务进度，取决于这里的写入结果。

### 12.4 发布空闲状态并返回最终响应
这一步在做什么：
服务发布 `IDLE` 状态，表示本轮处理结束，然后用 `tryParseJsonNode(modelResult.getValidResponse())` 解析主模型返回结果。能解析成 JSON 就返回 `JsonNode`，否则返回原始字符串。

为什么要做这一步：
前端需要明确知道本轮链路已经结束；调用方也需要拿到最终可消费的回复对象，而不是中间治理产物。

这一阶段的数据或状态发生了什么变化：
前端状态从处理中切回空闲；HTTP 响应体被确定为最终回复。

对后续流程有什么影响：
至此，`ChatController.message` 主链路完整结束。下一轮请求只能基于这一轮已经写回的状态、快照、缓存和记忆继续推进。
