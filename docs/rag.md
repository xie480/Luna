# 通用 RAG 模块设计文档

## 1. 文档目标

本文档仅描述 **RAG 模块本身** 的设计，不涉及业务层、人设层、Prompt 层或最终生成层。

目标：

- 保留 `search / native / modular / agentic` 四类链路
- 基于现有 PostgreSQL + pgvector 三张表实现
- 不修改现有表结构
- 将 RAG 抽象为一个 **业务无关、可复用的通用模块**
- 为开发提供可直接落地的模块边界、接口、流程和伪代码

---

## 2. 设计原则

### 2.1 模块职责边界
RAG 模块只负责：

1. 查询理解（面向检索）
2. 检索路由
3. 多源召回
4. rerank / fusion / 去重 / 压缩
5. 返回标准化 evidence

RAG 模块不负责：

- 人设
- 活人感
- Prompt 设计
- 最终答案生成
- 长期记忆写回
- 业务流程编排

### 2.2 数据源无关
模块对外暴露的是抽象 source：

- `knowledge`
- `memory`
- `preference`

不直接暴露具体表名。

### 2.3 链路分层
保留四条标准链路：

- `Search`
- `Native`
- `Modular`
- `Agentic`

### 2.4 标准化输出
所有数据源返回结果统一转为 `Evidence`，供上层使用。

---

## 3. 现有数据源映射

### 3.1 knowledge source
映射表：`knowledge_base`

用途：

- 文件解析内容
- 联网搜索结果
- 手动输入知识
- RAG 知识检索主表

### 3.2 memory source
映射表：`luna_memory`

用途：

- 长期记忆
- 历史事实
- 偏好型记忆
- 阶段摘要
- 反思类记忆

### 3.3 preference source
映射表：`user_preference`

用途：

- 用户画像
- 偏好配置
- 风格偏好
- 表达偏好

---

## 4. 模块总览

### 4.1 模块目标
构建一个统一入口的 Retrieval Orchestration Engine。

### 4.2 总体流程

```text
RetrievalRequest
    ↓
Query Processor
    ↓
Router
    ↓
Pipeline (Search / Native / Modular / Agentic)
    ↓
Retrievers (Knowledge / Memory / Preference)
    ↓
Ranker / Fusion / Compression
    ↓
RetrievalResponse
```

---

## 5. 模块目录建议

```text
rag_module/
├── api/
│   ├── service.py
│   └── schemas.py
├── processor/
│   ├── query_processor.py
│   └── rewrite.py
├── router/
│   ├── route_selector.py
│   └── rules.py
├── retrievers/
│   ├── base.py
│   ├── knowledge_retriever.py
│   ├── memory_retriever.py
│   └── preference_retriever.py
├── pipelines/
│   ├── base.py
│   ├── search_pipeline.py
│   ├── native_pipeline.py
│   ├── modular_pipeline.py
│   └── agentic_pipeline.py
├── rankers/
│   ├── reranker.py
│   ├── fusion.py
│   ├── dedup.py
│   └── compression.py
├── adapters/
│   ├── pg_adapter.py
│   └── embedding_provider.py
├── models/
│   ├── query.py
│   ├── route_plan.py
│   ├── evidence.py
│   └── response.py
└── config/
    ├── route_rules.yaml
    └── retrieval.yaml
```

---

## 6. 对外接口设计

### 6.1 统一入口

```python
retrieve(request: RetrievalRequest) -> RetrievalResponse
```

### 6.2 RetrievalRequest

```json
{
  "query": "结合我之前的情况，帮我分析最近为什么总拖着不做决定",
  "session_id": "user_001",
  "conversation_context": [
    {"role": "user", "content": "..."}, 
    {"role": "assistant", "content": "..."}
  ],
  "allowed_routes": ["search", "native", "modular", "agentic"],
  "source_scope": ["knowledge", "memory", "preference"],
  "options": {
    "debug": true,
    "max_latency_ms": 1200
  }
}
```

### 6.3 RetrievalResponse

```json
{
  "route": "modular",
  "rewritten_query": "结合用户过往记忆和知识，分析近期决策拖延原因",
  "evidences": {
    "knowledge": [],
    "memory": [],
    "preference": []
  },
  "meta": {
    "sources_used": ["knowledge", "memory", "preference"],
    "latency_ms": 96,
    "query_type": "multi_source_reasoning"
  }
}
```

---

## 7. 核心数据结构

### 7.1 QueryObject

```python
class QueryObject:
    query: str
    rewritten_query: str | None
    session_id: str | None
    conversation_context: list
    embedding: list[float] | None
    query_type: str | None
```

### 7.2 RoutePlan

```python
class RoutePlan:
    route: str
    sources: list[str]
    needs_rewrite: bool
    needs_rerank: bool
    query_type: str
    top_k_config: dict
```

### 7.3 Evidence

```python
class Evidence:
    id: str
    source: str            # knowledge / memory / preference
    type: str              # knowledge / memory / preference
    title: str | None
    content: str
    score: float
    metadata: dict
```

### 7.4 RetrievalResponse

```python
class RetrievalResponse:
    route: str
    rewritten_query: str | None
    evidences: dict[str, list[Evidence]]
    meta: dict
```

---

## 8. 四类链路设计

---

## 8.1 Search Pipeline

### 8.1.1 目标
用于精准查找、明确定位、低成本高精度检索。

### 8.1.2 适用场景
典型关键词：

- “有没有”
- “哪条”
- “那个”
- “上次”
- “某个设置”
- “某段记忆”
- “某条知识”

例子：

- 我之前记录过关于拖延的记忆吗
- 偏好里有没有关于回答长度的设置
- 知识库中关于复盘的那条内容是什么

### 8.1.3 策略
- 精确过滤优先
- FTS / trigram / keyword 优先
- embedding 补召回
- top-k 小
- precision-first

### 8.1.4 默认 top-k
- knowledge: 3
- memory: 3
- preference: 2

---

## 8.2 Native Pipeline

### 8.2.1 目标
用于单主源、单跳问题。

### 8.2.2 适用场景
- 单独查知识
- 单独查记忆
- 单独查偏好

例子：

- 什么是阶段复盘
- 我是不是更喜欢简洁表达
- 我过去一段时间最常提到的问题是什么

### 8.2.3 策略
- 选择一个主 retriever
- embedding 检索为主
- 可加轻量 hybrid
- 轻量 rerank

### 8.2.4 默认 top-k
- knowledge: 5
- memory: 5
- preference: 3

---

## 8.3 Modular Pipeline

### 8.3.1 目标
用于多源联合检索和可编排检索。

### 8.3.2 适用场景
- 需要 knowledge + memory
- 需要 memory + preference
- 需要 knowledge + memory + preference

例子：

- 结合我以前的情况，给我一个更适合的建议
- 根据我的长期记忆和知识库，分析我最近为什么总犹豫
- 按我的偏好，给我一个更适合阅读的总结

### 8.3.3 策略
1. query rewrite
2. source routing
3. 多 retriever 并行召回
4. 每源 rerank
5. 跨源 fusion
6. dedup
7. compression
8. evidence role grouping

### 8.3.4 默认 top-k
- knowledge: 5~8
- memory: 4~6
- preference: 2~3

---

## 8.4 Agentic Pipeline

### 8.4.1 目标
用于复杂分析、多步补查、动态检索。

### 8.4.2 适用场景
- 分析
- 比较
- 梳理
- 归纳模式
- 找原因
- 总结变化

例子：

- 帮我分析一下，我为什么这段时间总在同一个问题上反复卡住
- 比较我以前的目标和现在的想法，看看哪里变了
- 帮我总结出我最核心的几个稳定偏好和反复出现的问题模式

### 8.4.3 策略
1. 拆解子任务
2. 子任务级检索
3. 证据充分性判断
4. 动态补查
5. 汇总 evidence

### 8.4.4 限制
- 最大步数限制
- 最大调用次数限制
- 最大总 top-k 限制
- 超限 fallback 到 modular

---

## 9. Router 设计

### 9.1 Router 职责
Router 只做检索复杂度和检索模式判断，不做业务理解。

### 9.2 路由优先级

#### 优先级 1：是否精准查找
满足则 `search`

特征：
- 明确查某条
- 明确查某项
- 明确查某个历史对象
- 存在显著定位词

#### 优先级 2：是否单源可答
满足则 `native`

特征：
- 单主源即可回答
- 不需要跨表整合
- 不需要复杂补查

#### 优先级 3：是否多源联合
满足则 `modular`

特征：
- “结合我之前的情况”
- “按我的偏好”
- “根据过去记录和知识”
- 同时依赖两类及以上 source

#### 优先级 4：是否复杂分析
满足则 `agentic`

特征：
- 分析
- 比较
- 总结变化
- 找规律
- 找原因
- 梳理阶段性模式

### 9.3 示例规则

```python
def select_route(query: str, query_type: str, source_count: int) -> str:
    if is_precise_lookup(query):
        return "search"
    if is_analysis_query(query):
        return "agentic"
    if source_count == 1:
        return "native"
    return "modular"
```

---

## 10. Query Processor 设计

### 10.1 目标
在进入检索前，把 query 变成更适合召回的形式。

### 10.2 职责
- 清洗 query
- rewrite
- 指代补全
- 提取过滤信号
- 生成 embedding

### 10.3 输出字段
- original_query
- rewritten_query
- normalized_query
- embedding
- query_tags
- possible_filters

---

## 11. Retriever 设计

---

## 11.1 BaseRetriever

```python
class BaseRetriever:
    def retrieve(self, query_obj, top_k: int, filters: dict | None = None) -> list[Evidence]:
        raise NotImplementedError
```

---

## 11.2 KnowledgeRetriever

### 数据源
`knowledge_base`

### 召回方式
- pgvector
- FTS
- title/content keyword
- source_type filter

### Search 策略
- FTS 优先
- title 命中优先
- embedding 辅助

### Native / Modular 策略
- embedding 主召回
- FTS 补充
- source_type 参与排序

### 推荐融合分数

```text
knowledge_score =
  0.60 * vector_score
+ 0.25 * fts_score
+ 0.10 * recency_score
+ 0.05 * source_prior
```

### source_prior 建议
- MANUAL_INPUT(2): 1.0
- FILE(0): 0.8
- WEB_SEARCH(1): 0.6

---

## 11.3 MemoryRetriever

### 数据源
`luna_memory`

### 基础过滤
- `session_id = 当前请求 session_id`

### 可选过滤
- `memory_type`
- 时间窗口

### 召回方式
- embedding 主召回
- 必要时关键词补充
- 应用层按 weight / recency / type 加权

### 推荐融合分数

```text
memory_score =
  0.55 * vector_score
+ 0.20 * weight_score
+ 0.15 * recency_score
+ 0.10 * type_score
```

### type_score 建议
由 pipeline 决定，例如：

- advice 场景：FACT / PREFERENCE 更高
- reflective 场景：SUMMARY / REFLECTION 更高

---

## 11.4 PreferenceRetriever

### 数据源
`user_preference`

### 基础过滤
- `deleted = 0`

### 召回方式
- embedding
- pref_key 精确匹配
- pref_value / description trigram

### 推荐策略
- 结果少量且精确
- 只保留 1~3 条核心偏好

### 推荐融合分数

```text
preference_score =
  0.50 * vector_score
+ 0.20 * key_match_score
+ 0.20 * recency_score
+ 0.10 * text_match_score
```

---

## 12. Evidence 标准化

### 12.1 目标
屏蔽底层表结构差异，统一返回上层可消费对象。

### 12.2 示例

#### knowledge
```json
{
  "id": "knowledge:101",
  "source": "knowledge",
  "type": "knowledge",
  "title": "决策拖延的常见原因",
  "content": "......",
  "score": 0.88,
  "metadata": {
    "raw_id": 101,
    "source_type": 2,
    "source_path": "manual"
  }
}
```

#### memory
```json
{
  "id": "memory:12",
  "source": "memory",
  "type": "memory",
  "content": "用户多次提到害怕做错决定后承担后果",
  "score": 0.91,
  "metadata": {
    "raw_id": 12,
    "memory_type": 3,
    "weight": 5,
    "session_id": "user_001"
  }
}
```

#### preference
```json
{
  "id": "preference:3",
  "source": "preference",
  "type": "preference",
  "content": "response_style = 简洁、自然",
  "score": 0.95,
  "metadata": {
    "raw_id": 3,
    "pref_key": "response_style",
    "pref_value": "简洁、自然"
  }
}
```

---

## 13. Ranker / Fusion 设计

### 13.1 source 内 rerank
每个 source 先独立重排：

- knowledge 内排序
- memory 内排序
- preference 内排序

### 13.2 跨源 fusion
不建议把三类 source 完全混成一个总 top-k，而是：

- 分 source 输出
- 同时保留 source 内排序
- modular / agentic 可做轻量跨源重要性排序

### 13.3 dedup
需要处理：
- 相似知识片段重复
- 相似 memory 重复
- preference 冲突项重复

### 13.4 compression
只对 modular / agentic 启用。

方式：
- 片段截断
- 摘要压缩
- 合并相近证据

---

## 14. Pipeline 详细流程

---

## 14.1 SearchPipeline

### 输入
- query_obj
- route_plan

### 执行
1. 从 route_plan 选目标 source
2. 应用强过滤条件
3. 执行 keyword / FTS / exact 检索
4. embedding 补召回
5. source 内 rerank
6. 返回 evidence

### 输出要求
- top-k 小
- 高 precision
- 低延迟

---

## 14.2 NativePipeline

### 输入
- query_obj
- route_plan

### 执行
1. 选单主源 retriever
2. embedding 检索
3. 轻量 hybrid
4. source 内 rerank
5. 返回 evidence

### 输出要求
- 单源结果清晰
- 低成本
- 高吞吐

---

## 14.3 ModularPipeline

### 输入
- query_obj
- route_plan

### 执行
1. 可选 rewrite
2. source routing
3. 多 retriever 并行召回
4. 每源 rerank
5. fusion
6. dedup
7. compression
8. 分 source 输出 evidence

### 输出要求
- 支持多源联合
- 稳定可控
- 结构化 evidence

---

## 14.4 AgenticPipeline

### 输入
- query_obj
- route_plan

### 执行
1. 拆解子问题
2. 子问题映射 source
3. 分步检索
4. 判断是否证据不足
5. 动态补查
6. 合并 evidence
7. 返回结构化结果

### 输出要求
- 支持复杂分析
- 支持补检索
- 控制步数和成本

---

## 15. SQL 模板建议

以下为示意模板，实际实现可根据 ORM 或 SQL builder 调整。

---

## 15.1 knowledge vector retrieval

```sql
SELECT
    id,
    title,
    content,
    source_type,
    source_path,
    created_at,
    updated_at,
    1 - (embedding <=> :query_embedding) AS vector_score
FROM knowledge_base
WHERE embedding IS NOT NULL
ORDER BY embedding <=> :query_embedding
LIMIT :top_k;
```

---

## 15.2 knowledge hybrid retrieval

```sql
SELECT
    id,
    title,
    content,
    source_type,
    source_path,
    created_at,
    updated_at,
    ts_rank(
        to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, '')),
        plainto_tsquery('simple', :fts_query)
    ) AS fts_score,
    1 - (embedding <=> :query_embedding) AS vector_score
FROM knowledge_base
WHERE
    to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, ''))
    @@ plainto_tsquery('simple', :fts_query)
ORDER BY fts_score DESC
LIMIT :top_k;
```

---

## 15.3 memory retrieval

```sql
SELECT
    id,
    session_id,
    memory_type,
    content,
    weight,
    created_at,
    updated_at,
    1 - (embedding <=> :query_embedding) AS vector_score
FROM luna_memory
WHERE
    session_id = :session_id
    AND embedding IS NOT NULL
ORDER BY embedding <=> :query_embedding
LIMIT :top_k;
```

---

## 15.4 memory retrieval with type filter

```sql
SELECT
    id,
    session_id,
    memory_type,
    content,
    weight,
    created_at,
    updated_at,
    1 - (embedding <=> :query_embedding) AS vector_score
FROM luna_memory
WHERE
    session_id = :session_id
    AND memory_type = ANY(:memory_types)
    AND embedding IS NOT NULL
ORDER BY embedding <=> :query_embedding
LIMIT :top_k;
```

---

## 15.5 preference retrieval

```sql
SELECT
    id,
    pref_key,
    pref_value,
    description,
    created_at,
    updated_at,
    1 - (embedding <=> :query_embedding) AS vector_score
FROM user_preference
WHERE
    deleted = 0
    AND embedding IS NOT NULL
ORDER BY embedding <=> :query_embedding
LIMIT :top_k;
```

---

## 15.6 preference exact / trigram retrieval

```sql
SELECT
    id,
    pref_key,
    pref_value,
    description,
    created_at,
    updated_at
FROM user_preference
WHERE
    deleted = 0
    AND (
        pref_key = :pref_key
        OR pref_value % :keyword
        OR description % :keyword
    )
LIMIT :top_k;
```