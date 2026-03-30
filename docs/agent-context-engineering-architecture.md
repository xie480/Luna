# Luna Agent 上下文工程架构说明

## 1. 范围与定义
本文只描述 **Agent 应用中的上下文工程（Context Engineering）架构**，不覆盖全系统基础设施或通用业务模块。

上下文工程在本项目中的目标是：
- 在一次对话请求中，拼装“足够、可控、可追溯”的上下文给模型。
- 在上下文过大时进行有损压缩，但保证主链路可用。
- 在工具调用、审批中断、异步摘要后，保持上下文连续性。

## 2. 架构边界（与整体架构区分）
本架构的核心边界是以下链路：
1. 短期会话上下文读取与写入（Redis）。
2. 多源检索上下文（Knowledge/Preference/Memory）的获取与格式化。
3. 上下文裁剪（ContextPruner）与异步摘要压缩（MQ Consumer）。
4. 工具调用上下文注入（ToolCallingContext + ThreadLocal）与审批续跑。
5. 最终 Prompt 组装与模型调用。

## 3. 组件与职责
- `ChatServiceImpl`：上下文主编排入口，负责串联检索、裁剪、工具调用、Prompt 生成与会话落盘。
- `SessionServiceImpl`：维护短期会话上下文（Redis List），并在超阈值时触发压缩标记。
- `RetrievalServiceImpl`：统一 RAG 入口，组织 Query 处理、路由选择、Pipeline 执行、结果元信息补齐。
- `QueryProcessor`：查询规范化、重写、向量化，生成后续路由与检索所需的 QueryObject。
- `RouteSelector`：根据模型 hint + 规则启发选择 `SEARCH/NATIVE/MODULAR/AGENTIC`。
- `AbstractRetrievalPipeline` 及各 Pipeline：并发召回、去重、重排、压缩、跨源融合。
- `KnowledgeRetriever/PreferenceRetriever/MemoryRetriever`：从不同数据源提取证据片段。
- `PromptAssembler`：按固定区块顺序组装最终 Prompt（系统指令、检索上下文、工具上下文、会话记忆、用户输入）。
- `ContextPruner`：基于优先级做上下文裁剪（先删低优先级块）。
- `ContextSummaryConsumer`：异步消费摘要任务，将长会话压缩为 `CONTEXT_SUMMARY` 写回。
- `ToolCallingContext` + `ToolCallingContextHolder`：为工具执行与审批续跑传递上下文快照。

## 4. 上下文生命周期（请求级）

### 4.1 采集阶段
- 输入：用户当前输入。
- 短期记忆：从 Redis 读取会话历史（`SessionServiceImpl.getRecentMessages`）。
- 多源上下文：调用 `RetrievalService.retrieve`，产出三类片段：
  - `knowledgeSnippets`
  - `preferenceSnippets`
  - `longTermMemorySnippets`

### 4.2 压缩与裁剪阶段
- 长度触发：`SessionServiceImpl` 按字符总量阈值触发摘要标记。
- 异步摘要：`ChatServiceImpl` 投递 MQ，`ContextSummaryConsumer` 生成摘要并 `replaceHistoryWithSummary`。
- 请求内裁剪：`ContextPruner.prune` 按优先级淘汰上下文，保障 Prompt 长度控制。

### 4.3 工具链上下文注入阶段
- 在调用 Agent 工具决策前，将当前上下文快照写入 `ToolCallingContextHolder`。
- 若工具需审批，`ApprovalServiceImpl.createTaskAndInterrupt` 会持久化这份上下文。
- 审批通过/拒绝后，`ApprovalServiceImpl` 使用原上下文重新组装 Prompt，实现“中断后续跑”。

### 4.4 组装与生成阶段
`PromptAssembler.assembleFinalPrompt` 按固定顺序注入上下文：
1. System Prompt
2. Knowledge Base
3. User Preferences
4. Long-term Memory
5. Tool Context
6. Recent Chat Memory
7. Runtime/User Input

模型输出后：
- 写回会话（USER/LUNA）。
- 向外返回去除 `thought` 的 JSON。

## 5. 当前设计优点
1. 上下文分层清晰。
- 短期记忆、长期记忆、偏好、知识、工具结果被拆分为独立槽位，便于演进。

2. 检索链路具备可扩展性。
- 路由 + Pipeline + Retriever 结构使新增来源或检索策略成本较低。

3. 主链路容错较好。
- RAG 异常、修复失败等情况下有降级路径，不阻断对话返回。

4. 支持“工具审批中断后续跑”。
- 通过 `ToolCallingContext` 快照，审批后可继续生成而非重开新对话状态。

5. 同时具备“异步压缩 + 同步裁剪”双保险。
- 异步摘要用于长期体积治理；同步裁剪用于单请求内硬限制控制。

## 6. 当前设计缺点
1. 会话标识存在双轨。
- 短期会话 key 使用日期（如 `yyyy:MM:dd`），而 Memory 检索/工具执行更倾向 JWT `jti`，上下文一致性存在语义割裂。

2. 压缩触发标记为全局符号。
- `CONTEXT_SUMMARY_FLAG` 使用进程内全局 `SymbolMap`，不是 session 级别，存在并发会话互相干扰风险。

3. 长度控制以字符为准，不是 token 为准。
- `MAX_CHARACTERS/MAX_PROMPT_CHARS` 可能与真实模型 token window 偏差较大，导致裁剪不稳定。

4. 裁剪策略偏“硬删除”。
- `ContextPruner` 会整块清空低优先级上下文，缺少“按分数保留部分片段”的细粒度策略。

5. Prompt 模板过重且强耦合。
- System Prompt 与格式约束体量很大，且与业务逻辑直接拼接，维护和 A/B 调优成本高。

6. 工具上下文传递依赖 ThreadLocal。
- 必须严格清理；未来如引入更多异步/并行链路，容易出现上下文泄漏或丢失边界问题。

## 7. 结论
当前 Agent 的上下文工程架构已经形成“可运行闭环”：
- 上下文能被采集、增强、裁剪、注入、落盘，并支持审批中断续跑。

但在“多会话一致性、并发隔离、token 精确预算、细粒度裁剪”上仍有明显改进空间。整体上属于 **可用且可扩展，但需要进一步工程化稳固** 的阶段。

## 8. 关键代码定位
- `src/main/java/org/yilena/luna/service/impl/ChatServiceImpl.java`
- `src/main/java/org/yilena/luna/service/impl/SessionServiceImpl.java`
- `src/main/java/org/yilena/luna/prompt/PromptAssembler.java`
- `src/main/java/org/yilena/luna/utils/ContextPruner.java`
- `src/main/java/org/yilena/luna/mq/consumer/ContextSummaryConsumer.java`
- `src/main/java/org/yilena/luna/rag/api/RetrievalServiceImpl.java`
- `src/main/java/org/yilena/luna/rag/processor/QueryProcessor.java`
- `src/main/java/org/yilena/luna/rag/router/RouteSelector.java`
- `src/main/java/org/yilena/luna/rag/pipelines/AbstractRetrievalPipeline.java`
- `src/main/java/org/yilena/luna/service/impl/ApprovalServiceImpl.java`
- `src/main/java/org/yilena/luna/entity/ToolCallingContext.java`
- `src/main/java/org/yilena/luna/utils/ToolCallingContextHolder.java`
