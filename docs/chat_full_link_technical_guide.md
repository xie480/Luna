# Chat 全链路技术文档（现状 + 优化方案）

## 1. 文档目标

本文档面向后端开发、性能优化及运维同学，详细说明当前 `chat` 请求在系统中的完整链路，包括：

1. 请求进入后的处理阶段
2. 每个阶段的核心组件与关键代码位置
3. 关键耗时点与性能瓶颈
4. 可落地的优化策略（短中长期）
5. 整体响应速度提升路线图与验证方法

---

## 2. 系统链路总览

当前主入口：

- 控制器：`src/main/java/org/yilena/luna/controller/ChatController.java`
- 服务实现：`src/main/java/org/yilena/luna/service/impl/ChatServiceImpl.java`
- 核心方法：`chat(ChatRequest chatRequest)`

一次 `/luna/api/chat/message` 请求大致按以下顺序执行：

1. 鉴权拦截（`AuthInterceptor`）
2. 请求体缓存过滤（`RequestCachingFilter`）
3. 进入 `ChatController#chat`
4. 进入 `ChatServiceImpl#chat`
5. SSE 推送状态 `THINKING`
6. 并行 RAG 阶段（虚拟线程池）  
   - query 向量化  
   - 知识库向量检索 + rerank  
   - 用户偏好向量检索 + rerank  
   - 长期记忆向量检索 + rerank
7. 加载近期会话上下文（Redis）
8. 按阈值触发上下文压缩（MQ 异步）
9. 工具决策与执行（Agent + MCP）
10. Prompt 组装（System + Memory + RAG + ToolContext + Runtime）
11. 调用大模型生成回复
12. 结果 JSON 校验与修复（必要时 REPAIR_PROMPT）
13. 写回会话（Redis）
14. SSE 状态恢复 `IDLE`
15. 返回响应

---

## 3. 分阶段详细说明

## 阶段 A：入口与基础校验

### A.1 HTTP 入口
- `ChatController#chat` 仅负责转发请求到 `ChatService#chat`，是薄控制器设计。

### A.2 输入校验
- `ChatServiceImpl#chat` 中对 `chatRequest.getUserInput()` 做了 `null/trim` 校验。
- 空输入直接返回 400，避免后续无效链路耗时。

### A.3 状态推送
- 一进来即推送 `THINKING`，提升前端体感。

---

## 阶段 B：并行 RAG（当前链路中最核心的性能段）

代码位置：`ChatServiceImpl#chat` 并行块（`newThreadPerTaskExecutor(Thread.ofVirtual()...)`）

### B.1 设计
使用虚拟线程池并发执行三类检索，最大限度压缩等待时间：

1. `queryVectorFuture`：用户输入向量化（`LlmClientUtil#getEmbedding`）
2. `kbFuture`：知识库检索（`KnowledgeBaseService#searchKnowledge`）+ rerank
3. `preferenceFuture`：偏好向量检索（`UserPreferenceMapper#searchByVector`）+ rerank
4. `memoryFuture`：记忆向量检索（`MemoryMapper#searchByVector`）+ rerank

### B.2 超时控制
- 每路 future 设置 `completeOnTimeout(...)`，防止单点阻塞拖垮整体响应。
- 当前统一超时值：`RAG_TIMEOUT_MS = 2500`。

### B.3 rerank策略
- 当命中数量较小（`<= RAG_TOP_K_FINAL`）时，跳过 rerank，减少 Python 进程开销。
- 命中较多时执行 rerank 提升相关性。

### B.4 当前优点
- 并行化显著优于串行。
- 各分支失败可降级为空列表，不影响主链路可用性。

### B.5 当前瓶颈
1. `rerank.py` 每次都起新进程 + 模型加载，冷启动成本高。
2. embedding 走 Python 子进程，仍有进程启动与IPC开销。
3. 三个向量检索都依赖数据库 cast + operator，SQL计划稳定性依赖索引与统计信息。
4. 并发 rerank 可能造成 CPU 抢占（尤其 Python 推理重）。

---

## 阶段 C：会话上下文与压缩

### C.1 Redis 会话读取
- `SessionServiceImpl#getRecentMessages` 从 Redis list 读历史对话。
- 当字符数超阈值触发 `CONTEXT_SUMMARY_FLAG`。

### C.2 异步压缩
- `ChatServiceImpl` 检测到 flag 后，发 MQ 到 `luna_summary_topic`。
- 消费端 `ContextSummaryConsumer` 调用模型生成摘要，再回写 Redis。

### C.3 优点与风险
优点：
- 压缩异步化，不阻塞当前对话主路径。

风险：
- 当前请求可能读取到压缩前上下文，下一请求才看到压缩结果（最终一致性）。

---

## 阶段 D：工具决策与执行（Agent）

代码位置：`AgentServiceImpl#processToolCalling`

1. `ToolRouter.findCandidates` 取候选资源（内部走 `McpService#searchResources`）
2. LLM 决策是否调用工具
3. 生成参数 JSON
4. JSON Schema 校验与修复
5. `ExecutionGate` 安全检查
6. Tool/Skill 执行（`ReflectionToolExecutor` / `SkillExecutor`）

性能特点：
- 此阶段会触发额外模型调用（决策 + 参数），开销非小。
- 但能减少主模型 hallucination，提升可控性与正确率。

---

## 阶段 E：Prompt 组装与主模型生成

### E.1 Prompt组装
`PromptAssembler#assembleFinalPrompt` 组合：
- System Prompt
- Knowledge Base Prompt
- Tool Context Prompt
- Memory Prompt
- Runtime Prompt

### E.2 主模型调用
`ChatServiceImpl#getSendToLuna -> LlmClientUtil#generate`

- 正常路径：返回 JSON 字符串，解析提取 `reply`
- 异常路径：触发 `REPAIR_PROMPT` 修复
- 最终兜底：固定降级 JSON

### E.3 安全检测开关
- `LlmRequest.enablePromptInjectionCheck` 控制注入检测。
- chat主调用默认开启；内部修复/agent任务可关闭。

---

## 阶段 F：落库/日志/返回

1. 对话写回 Redis：USER 与 LUNA 消息追加
2. AOP日志：`LunaLogAspect` 组装日志并投递 RocketMQ
3. `LunaLogConsumer` 异步落库 `luna_log`
4. SSE 推送 `IDLE`
5. 返回前移除 thought 字段，输出对前端安全

---

## 4. 当前已具备的性能优化点（现状）

1. **虚拟线程并行RAG**：缩短关键等待路径
2. **RAG分支超时降级**：避免长尾阻塞
3. **命中少时跳过rerank**：减少不必要推理
4. **Embedding缓存**（进程内）：减少重复向量化
5. **上下文压缩异步化**：减轻主路径负担
6. **SQL 索引**（BTree + ivfflat）：向量检索与过滤检索加速
7. **JSON修复兜底**：失败可恢复，减少重试成本
8. **MQ日志异步化**：降低主业务同步开销

---

## 5. 可进一步优化点（重点）

## 5.1 最高优先级（立刻见效）

### 5.1.1 rerank服务常驻化（替代每次起Python进程）
问题：
- 当前 `rerank` 每次起进程 + 加载模型，成本很高。

建议：
- 将 rerank 改为常驻服务（HTTP/gRPC）：
  - Java 仅发请求，不再拉起 python 子进程
  - 模型常驻内存，吞吐和延迟大幅改善

收益：
- 单次 rerank 延迟可下降明显（尤其并发场景）。

---

### 5.1.2 embedding服务常驻化
问题：
- embedding 目前同样是子进程模式。

建议：
- 与 rerank同理，拆出 embedding 推理服务。
- 保留 Java 侧缓存并增加 TTL/LRU 机制。

收益：
- 减少进程创建成本，提升稳定性与可观测性。

---

### 5.1.3 RAG分级策略（先快后精）
建议：
1. 第一步先取 DB topN（例如 20）
2. 若 query 简单/响应预算紧，直接截断返回 topK
3. 若 query 复杂/命中分布分散，再触发 rerank

收益：
- 在不明显牺牲质量前提下进一步降时延。

---

## 5.2 数据库层优化

### 5.2.1 向量列类型统一
当前已按 `vector(768)` 方向统一，需确保：
- 所有表 embedding 字段都实际为 `vector(768)`
- SQL中尽量减少 runtime cast

### 5.2.2 向量索引参数调优
ivfflat `lists=100` 为通用值，建议按数据量调：
- 数据量大：增大 lists
- 查询实时性优先：调优 `ivfflat.probes`

示例（会话级）：
```sql
SET ivfflat.probes = 10;
```

### 5.2.3 高频过滤索引完善
已存在多处BTree索引，建议结合慢查询日志复核：
- 是否存在组合索引需求（如 `log_type + create_at`）
- 是否存在低选择性索引浪费

### 5.2.4 统计信息与Vacuum
向量检索效果对统计信息敏感，建议定期：
- `ANALYZE`
- `VACUUM (ANALYZE)`

---

## 5.3 Agent链路优化

### 5.3.1 决策缓存
对高频query的工具决策结果做短TTL缓存（例如30s~2min）：
- 命中缓存可跳过决策模型调用

### 5.3.2 参数模板化
同一工具常见参数模式可模板填充，减少参数生成模型调用次数。

### 5.3.3 候选裁剪更激进
`ToolRouter` 当前最多10个候选，可进一步结合意图分类缩至3~5。

---

## 5.4 Prompt与上下文优化

### 5.4.1 Token预算器
在组装前估算 token，按优先级裁剪，避免模型端超长截断。

### 5.4.2 分层Prompt缓存
- System Prompt 固定部分缓存
- Memory模板固定部分缓存
- 降低字符串拼接与GC压力

### 5.4.3 工具结果摘要化
将大体量工具结果先局部摘要再拼接，减少主模型输入长度和推理时间。

---

## 5.5 并发与资源治理

### 5.5.1 线程池与限流
虽然是虚拟线程，但下游资源仍有限：
- 对 DB、Python服务、外部API 加并发阈值
- 防止突发流量把下游压垮

### 5.5.2 熔断与降级策略
- rerank超时自动跳过
- preference/memory检索超时可直接空列表
- tool调用失败时主回复保持可用

### 5.5.3 观测指标
建议打点（Micrometer/Prometheus）：
- chat_total_latency
- rag_latency_breakdown
- embedding_latency
- rerank_latency
- tool_call_latency
- repair_prompt_rate
- fallback_rate

---

## 6. 目标时延拆解建议（示例）

可设目标：
- P50 < 1.5s
- P95 < 3.0s
- P99 < 5.0s

分段预算（示例）：
1. 输入校验+状态推送：50ms
2. 并行RAG（含向量化）：600~1200ms
3. Agent工具链路：200~700ms（无工具可更低）
4. 主模型生成：500~1200ms
5. 后处理+返回：50~150ms

---

## 7. 推荐落地顺序（Roadmap）

### 第1阶段（1~2天）
1. 增加全链路埋点与耗时日志（分阶段）
2. 开启慢SQL日志并抓TopN
3. 优化 `ivfflat.probes` 与索引核查

### 第2阶段（3~5天）
1. rerank常驻服务化
2. embedding常驻服务化
3. Agent决策缓存

### 第3阶段（持续）
1. Token预算器 + Prompt缓存
2. 分层降级策略完善
3. AB测试：质量与速度平衡

---

## 8. 验证方案（必须执行）

1. 压测场景：
   - 无工具普通问答
   - 触发工具问答
   - 高并发并行RAG场景
2. 对比指标：
   - 优化前后 P50/P95/P99
   - 超时率、fallback率、repair率
   - DB CPU、应用CPU、内存、GC
3. 质量回归：
   - 回复相关性
   - tool调用正确率
   - JSON合规率

---

## 9. 结论

当前 chat 链路已经具备较好的工程化基础（并行RAG、异步压缩、缓存、索引、兜底修复）。  
若要进一步显著提速，最关键的抓手是：

1. **Python推理子进程服务化（embedding + rerank）**
2. **数据库向量检索参数持续调优**
3. **Agent链路减少不必要模型调用**
4. **全链路可观测 + 有预算的降级策略**

按本文路线推进，整体响应速度和稳定性都会有明显提升，同时保持回复质量可控。
