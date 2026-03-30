# Luna RAG 架构与 LLM 使用点说明（基于当前代码）

> 代码基线：`src/main/java/org/yilena/luna/rag/**` 及其上游调用方。  
> 本文目标：扫描当前 RAG 系统，并重点标注“哪些地方用到了 LLM/模型，哪些地方没有”。

---

## 1. 结论先看（LLM 触点总览）

### 1.1 标签说明

- `LLM-GEN`：调用 `llmClientUtil.generate(...)`（生成式大模型）
- `EMBEDDING`：调用 `llmClientUtil.getEmbedding(...)`（向量模型）
- `RERANK`：调用 `llmClientUtil.rerank(...)`（重排模型）
- `NO-LLM`：纯规则、纯数据库或纯字符串处理

### 1.2 在线检索链路（Query Time）

| 阶段 | 组件 | 类型 | 说明 |
|---|---|---|---|
| Query 规划 | `ModelDrivenRagPlanner.planQuery` | `LLM-GEN` | 生成 `query_type/rewritten_query/route_hint/complexity` |
| Query 向量化 | `LlmEmbeddingProvider.embedding` | `EMBEDDING` | 对重写 query 生成向量 |
| 路由选择 | `RouteSelector.selectPlan` | `NO-LLM` | 优先 `route_hint`，否则关键词启发式 |
| 多源召回 | `*Retriever -> PgRetrievalAdapter -> *Mapper.searchByVector` | `NO-LLM` | pgvector 检索 |
| 每源后处理策略规划 | `ModelDrivenRagPlanner.planSourceProcessing` | `LLM-GEN` | 决定 dedup/rerank/compress/topK |
| 每源重排执行 | `EvidenceReranker.rerank` | `RERANK` | 对候选证据重排 |
| 每源去重/压缩 | `EvidenceDeduplicator/EvidenceCompressor` | `NO-LLM` | 内容去重 + 截断 |
| 跨源全局重排 | `ModelDrivenRagPlanner.rerankGlobally` | `LLM-GEN` | 融合后跨源排序 |
| Prompt 组装 | `PromptAssembler.assembleFinalPrompt` | `NO-LLM` | 拼装 system/memory/knowledge/preference/tool/user 输入 |
| 最终回复生成 | `ChatServiceImpl.getSendToLuna` | `LLM-GEN` | 聊天主模型输出回复（严格来说在 RAG 之后） |

### 1.3 离线入库链路（Index Time）

| 链路 | 组件 | 类型 | 说明 |
|---|---|---|---|
| 知识库入库 | `KnowledgeBaseConsumer` | `EMBEDDING` | 文本分片后逐片向量化入库 |
| 记忆入库 | `MemoryTools.manageMemory(INSERT)` | `EMBEDDING` | `sessionId + memoryType + content` 向量化 |
| 偏好入库 | `PreferenceTools.manageUserPreference(INSERT)` | `EMBEDDING` | `prefKey + prefValue + description` 向量化 |

结论：当前 RAG 不是“全链路都在跑生成式 LLM”。`LLM-GEN` 主要在规划与全局排序，检索本体是 pgvector，入库/检索都强依赖 embedding。

---

## 2. 总体架构

当前系统有两条主链路：

1. 在线检索链路（Query Time）
- 入口：`ChatServiceImpl -> RetrievalServiceImpl`
- 目标：召回 `knowledge/memory/preference` 证据并注入 prompt

2. 离线入库链路（Index Time）
- 入口：`KnowledgeBaseConsumer`、`MemoryTools`、`PreferenceTools`
- 目标：把文本转向量并落库到 PostgreSQL(pgvector)

---

## 3. 在线链路分阶段说明（重点标注 LLM）

### 阶段 A：请求进入 RAG（`RetrievalServiceImpl`）

- 输入：`RetrievalRequest(query, sessionId, allowedRoutes, sourceScope, options.maxLatencyMs)`
- 类型：`NO-LLM`
- 作用：统一编排，不直接调用模型

### 阶段 B：Query 预处理（`QueryProcessor`）

1. `planQuery(...)`：`LLM-GEN`
- 组件：`ModelDrivenRagPlanner.planQuery`
- 输出：`query_type / rewritten_query / route_hint / complexity`

2. `embedding(rewrittenQuery)`：`EMBEDDING`
- 组件：`EmbeddingProvider -> LlmEmbeddingProvider`
- 输出：`queryObject.embedding`

### 阶段 C：路由决策（`RouteSelector`）

- 类型：`NO-LLM`
- 行为：优先使用 `route_hint`，否则按关键词/`sourceScope` 启发式选 `SEARCH/NATIVE/MODULAR/AGENTIC`

### 阶段 D：并行召回（`AbstractRetrievalPipeline.retrieveBySources`）

- 类型：`NO-LLM`
- 行为：并行调用 `KnowledgeRetriever/MemoryRetriever/PreferenceRetriever`
- 落点：`PgRetrievalAdapter -> Mapper.searchByVector(...)`

### 阶段 E：每源后处理（`processSourceEvidence`）

1. 每源策略规划：`LLM-GEN`
- 组件：`ModelDrivenRagPlanner.planSourceProcessing`
- 产出：`deduplicate/rerank/compress/top_k/compression_chars`

2. 每源重排执行：`RERANK`
- 组件：`EvidenceReranker.rerank`

3. 去重/压缩执行：`NO-LLM`
- 组件：`EvidenceDeduplicator`、`EvidenceCompressor`

### 阶段 F：跨源融合（`EvidenceFusionService`）

1. 全局去重 + 分桶回填：`NO-LLM`
2. 全局重排：`LLM-GEN`
- 组件：`ModelDrivenRagPlanner.rerankGlobally`

### 阶段 G：响应封装（`RetrievalServiceImpl`）

- 类型：`NO-LLM`
- 输出：`RetrievalResponse(route, rewrittenQuery, evidences, meta)`

### 阶段 H：Prompt 注入 + 最终回答

1. `PromptAssembler.assembleFinalPrompt(...)`：`NO-LLM`
2. `ChatServiceImpl.getSendToLuna(...)`：`LLM-GEN`
- 调用：`llmClientUtil.generate(...)`
- 说明：这一步是“最终回答生成”，属于 RAG 下游，但通常被一起视为问答主链路

---

## 4. Agentic 与其他 Pipeline 的差异（LLM 视角）

### `SEARCH/NATIVE/MODULAR`

- 都会经过：
- `planQuery` (`LLM-GEN`)
- query embedding (`EMBEDDING`)
- 每源 `planSourceProcessing` (`LLM-GEN`)
- 可选每源 `rerank` (`RERANK`)
- `rerankGlobally` (`LLM-GEN`)

### `AGENTIC` 额外增加

1. `planAgentStages`：`LLM-GEN`
- 拆成多阶段目标与 source 组合

2. 每阶段可重写 query 并重算 embedding：`EMBEDDING`
- `AgenticPipeline.buildStageQuery`

3. 每阶段都会触发一轮 `retrieveBySources`
- 也意味着每阶段可能再次触发：
- `planSourceProcessing` (`LLM-GEN`)
- 每源 `rerank` (`RERANK`)
- 阶段级 `rerankGlobally` (`LLM-GEN`)

4. 结束后还有一次最终 fusion
- 再触发一次全局 `rerankGlobally` (`LLM-GEN`)

因此，`AGENTIC` 是当前最“模型密集”的路由。

---

## 5. 端到端调用图（含 LLM 标记）

```text
ChatServiceImpl.chat()
  -> RetrievalServiceImpl.retrieve()                                  [NO-LLM]
     -> QueryProcessor.process()
        -> ModelDrivenRagPlanner.planQuery()                          [LLM-GEN]
        -> EmbeddingProvider.embedding()                              [EMBEDDING]
     -> RouteSelector.selectPlan()                                    [NO-LLM]
     -> pipeline.execute()
        -> AbstractRetrievalPipeline.retrieveBySources()
           -> BaseRetriever.retrieve() x N                            [NO-LLM]
              -> PgRetrievalAdapter -> *Mapper.searchByVector(...)    [NO-LLM]
           -> ModelDrivenRagPlanner.planSourceProcessing()            [LLM-GEN]
           -> EvidenceReranker.rerank()                               [RERANK]
           -> EvidenceDeduplicator / EvidenceCompressor               [NO-LLM]
           -> EvidenceFusionService.fuse()
              -> global dedup / redistribute                          [NO-LLM]
              -> ModelDrivenRagPlanner.rerankGlobally()               [LLM-GEN]
  -> PromptAssembler.assembleFinalPrompt(...)                         [NO-LLM]
  -> ChatServiceImpl.getSendToLuna()                                  [LLM-GEN]
```

---

## 6. 离线入库链路（Index Time）

### 6.1 知识库入库（`KnowledgeBaseConsumer`）

1. `TextSplitter.splitText(content, 500, 50)`（`NO-LLM`）
2. 每个 chunk 调 `llmClientUtil.getEmbedding(chunk)`（`EMBEDDING`）
3. 保存 `knowledge_base`（含 `embedding`）

### 6.2 记忆入库（`MemoryTools`）

- `manageMemory(action=INSERT)`：
- 组装 embeddingText
- `llmClientUtil.getEmbedding(embeddingText)`（`EMBEDDING`）
- 写 `luna_memory`

### 6.3 偏好入库（`PreferenceTools`）

- `manageUserPreference(action=INSERT)`：
- 组装 embeddingText
- `llmClientUtil.getEmbedding(embeddingText)`（`EMBEDDING`）
- 写 `user_preference`

---

## 7. 降级与回退行为（和 LLM 相关）

### 7.1 `LLM-GEN` 回退

- `ModelDrivenRagPlanner.callJson` 失败时：
- `planQuery` 回退到规则结果
- `planSourceProcessing` 回退到默认策略
- `planAgentStages` 回退到单阶段默认计划
- `rerankGlobally` 回退到本地分数排序

### 7.2 `EMBEDDING` 回退

- `LlmEmbeddingProvider` 出错返回 `null`，主链路不中断
- 检索器遇到空向量会返回空结果（不会抛错）

### 7.3 `RERANK` 回退

- `EvidenceReranker` 异常时使用原顺序截断 `topK`

### 7.4 最终回答回退

- `ChatServiceImpl.getSendToLuna`：
- 首次生成不合法 -> 二次 repair prompt
- repair 仍失败 -> 本地兜底 JSON

---

## 8. 可观测字段（`RetrievalResponse.meta`）

常见字段：

- `latency_ms`
- `query_type`
- `session_id`
- `sources_used`
- `hit_sources`
- `timed_out_sources`
- `timeout_ms`
- `global_candidates`
- `global_after_dedup`
- `global_dedup_removed`
- `agentic_stage_count`
- `agentic_stages`
- `agentic_timeout_reached`

建议重点监控：

- `timed_out_sources`（召回稳定性）
- `global_dedup_removed`（候选重复度）
- `agentic_stage_count` + `latency_ms`（复杂查询成本）

---

## 9. 关键代码定位（按 LLM 相关性排序）

### 高优先（直接触发模型调用）

- `src/main/java/org/yilena/luna/rag/planner/ModelDrivenRagPlanner.java`
- `src/main/java/org/yilena/luna/rag/adapters/LlmEmbeddingProvider.java`
- `src/main/java/org/yilena/luna/rag/rankers/EvidenceReranker.java`
- `src/main/java/org/yilena/luna/service/impl/ChatServiceImpl.java`
- `src/main/java/org/yilena/luna/utils/LlmClientUtil.java`

### 中优先（RAG 主流程编排）

- `src/main/java/org/yilena/luna/rag/api/RetrievalServiceImpl.java`
- `src/main/java/org/yilena/luna/rag/processor/QueryProcessor.java`
- `src/main/java/org/yilena/luna/rag/router/RouteSelector.java`
- `src/main/java/org/yilena/luna/rag/pipelines/AbstractRetrievalPipeline.java`
- `src/main/java/org/yilena/luna/rag/pipelines/SearchPipeline.java`
- `src/main/java/org/yilena/luna/rag/pipelines/NativePipeline.java`
- `src/main/java/org/yilena/luna/rag/pipelines/ModularPipeline.java`
- `src/main/java/org/yilena/luna/rag/pipelines/AgenticPipeline.java`
- `src/main/java/org/yilena/luna/rag/fusion/EvidenceFusionService.java`

### 离线入库

- `src/main/java/org/yilena/luna/mq/consumer/KnowledgeBaseConsumer.java`
- `src/main/java/org/yilena/luna/tools/MemoryTools.java`
- `src/main/java/org/yilena/luna/tools/PreferenceTools.java`

### 检索数据访问（非 LLM）

- `src/main/java/org/yilena/luna/rag/retrievers/KnowledgeRetriever.java`
- `src/main/java/org/yilena/luna/rag/retrievers/MemoryRetriever.java`
- `src/main/java/org/yilena/luna/rag/retrievers/PreferenceRetriever.java`
- `src/main/java/org/yilena/luna/rag/adapters/PgRetrievalAdapter.java`

---

## 10. 一句话总结

当前 RAG 的“LLM 使用重点”在 `ModelDrivenRagPlanner`（规划与全局重排）；“检索本体”主要是向量数据库；离线入库与在线查询都依赖 embedding；最终回复生成由 `ChatServiceImpl` 在 RAG 之后调用主模型完成。
