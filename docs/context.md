# Luna 基于 Small Agent 的上下文工程架构设计（正式版）

## 1. 文档目标

本文基于 Luna 当前已有的 Agent 上下文工程、MCP、RAG 与 Memory 架构，给出一版正式的、可落地的 **基于 Small Agent 的上下文工程架构方案**。该方案的核心目标是把现有“Prompt 拼装闭环”升级为“面向任务节点的上下文治理系统”，并满足以下要求：

- 不再直接以用户原始输入驱动 RAG、MCP、蓝图生成与意图判断
- 引入 small agent 处理高模糊度的上下文重构、重排、转译与摘要
- 保留系统层的状态控制、快照恢复、流程编排与结构校验
- 与当前 MCP、RAG、Memory 主链路兼容演进
- 裁剪强调**语义保持**，不是文本截断
- 支持可回放、可审计、可定位问题的上下文生命周期治理 [1][2][5]

---

## 2. 设计背景与问题定义

### 2.1 当前系统的基础能力

Luna 当前的 Agent 上下文工程已经具备请求级上下文生命周期闭环：  
系统会采集用户输入、从 Redis 读取会话历史，并通过统一检索服务获取知识片段、偏好片段和长期记忆片段 [1]。  
在工具链路中，系统已支持在工具决策前写入上下文快照，并在审批中断场景下持久化上下文，审批后基于原上下文重新组装 Prompt，实现中断后续跑 [1]。  
整体上，当前架构已处于“可运行闭环、可扩展但仍需进一步工程化稳固”的阶段 [1]。

### 2.2 当前系统面临的核心问题

进入生产后，Agent 的主要问题通常不是某个提示词没写好，而是模型每一轮究竟看到了什么信息，这些信息是否相关、是否过时、是否被噪声淹没、是否和当前任务节点匹配 [2]。  
典型问题包括：

- 长对话中任务状态漂移
- 检索到了正确资料，但最终答案建立在无关内容上
- 工具结果返回正确，模型却误解或重复调用
- 历史记忆、当前状态、检索知识混在一起，难以治理 [2]

### 2.3 本方案的核心转变

本方案的核心不是继续增加上下文来源，而是：

1. 把上下文从“输入文本”升级为“任务工作集”
2. 把 `PromptAssembler` 升级为 `Context Assembler`
3. 在系统控制骨架中插入多个 small agent 处理高模糊度环节
4. 让 RAG、MCP、Memory 都成为候选上下文来源，而不是直接注入物
5. 让主模型只消费经过治理的、与当前节点强相关的工作集 [2]

---

## 3. 总体设计原则

### 3.1 系统主导，Small Agent 辅助

整体架构采用**系统中心**思路：  
系统负责确定性控制，small agent 负责高模糊语义处理。  
系统负责：

- 节点编排
- 显式状态维护
- MCP/RAG/Memory 调度
- 上下文快照保存
- 结构校验
- token 预算执行
- 中断恢复

small agent 负责：

- 用户输入重构
- 全局语义重排
- 工具结果语义转译
- 双摘要生成
- 恢复场景上下文重建 [2]

### 3.2 上下文是任务工作集，不是大 Prompt

上下文工程关注的不是“把多少信息塞给模型”，而是“当前节点真正需要什么信息工作集”。  
进入模型的内容必须兼顾：

- 完整性
- 相关性
- 时效性
- 结构性
- 成本约束 [2]

### 3.3 知识、状态、记忆分开治理

知识、状态、记忆虽然都可能进入模型上下文，但治理目标完全不同：

- **知识**：按需召回，强调召回质量与时效性
- **状态**：结构化显式控制，强调准确性与可回放
- **记忆**：跨轮或跨会话复用，强调筛选、沉淀、防污染 [2]

### 3.4 不再以用户原文直驱下游能力

用户原始输入保留为事实，但**不再直接**作为以下模块的输入依据：

- RAG Query
- MCP 候选能力检索
- 蓝图生成
- 意图判断
- 阶段规划

统一改为：  
先通过 small agent 基于短期记忆、当前状态、最近关键事件等信息对输入进行重构，再驱动下游能力。

### 3.5 裁剪不是截断，必须保持语义

上下文裁剪不是简单删前文或截断文本。  
裁剪的目标是：

- 去重
- 聚类合并
- 字段抽取
- 模板化重写
- 证据块压缩
- 摘要替换

而不是改变原始约束和任务语义 [2]。

---

## 4. 架构总览

建议将整体上下文工程划分为 10 个层级：

1. 会话入口层
2. 任务编排层
3. 显式状态层
4. 记忆接入层
5. 输入重构层
6. 多路召回层（RAG / MCP / Memory）
7. 分层重排层
8. 工具结果转译层
9. 上下文组装层
10. 摘要、快照与可观测层

其中，最关键的新增能力是 4 类 Small Agent：

- `Input Reconstruction Agent`
- `Global Context Rerank Agent`
- `Tool Semantic Agent`
- `Summary Agent`

可选增加：

- `Recovery Context Agent`

---

## 5. 核心组件设计

---

### 5.1 Task Orchestrator（任务编排器）

#### 5.1.1 职责

任务编排器负责：

- 判断当前请求进入哪个执行节点
- 协调普通对话、RAG 检索、MCP 工具、蓝图生成、摘要、恢复等流程
- 调用 `Context Assembler` 生成当前节点工作集
- 触发主模型或 small agent 执行

#### 5.1.2 输入

- 用户原始输入
- 当前会话标识
- 当前 `TaskState`
- 当前 `RetrievalState`
- 当前 `ToolState`
- 当前 `ContextState`
- 恢复事件 / 审批事件
- 最近一轮主模型输出

#### 5.1.3 输出

- 节点执行指令
- Small Agent 调用请求
- RAG 调用请求
- MCP 调用请求
- 主模型调用请求

---

### 5.2 State Store（显式状态层）

上下文历史是叙事性的，不适合承担复杂任务控制。复杂 Agent 的骨架应当是显式状态 [2]。

#### 5.2.1 TaskState

表示任务级控制骨架：

- `taskId`
- `sessionId`
- `objective`
- `currentStage`
- `currentNode`
- `confirmedSlots`
- `pendingQuestions`
- `finishedSteps`
- `failedSteps`
- `retryCount`
- `nextActionHint`

#### 5.2.2 RetrievalState

表示检索相关状态：

- `reconstructedIntent`
- `activeQueries`
- `retrievalPlan`
- `selectedEvidenceRefs`
- `rerankSummary`

#### 5.2.3 ToolState

表示工具链路状态：

- `lastToolName`
- `lastToolInput`
- `lastToolStatus`
- `lastToolRawResultRef`
- `lastToolSemanticSummary`
- `toolCallHistoryRefs`

#### 5.2.4 ContextState

表示上下文工程侧产物：

- `latestNarrativeSummary`
- `latestStateSnapshot`
- `activeKnowledgeRefs`
- `activeMemoryRefs`
- `activeToolEvidenceRefs`
- `activeMcpPromptRefs`
- `activeMcpResourceRefs`
- `latestContextSnapshotId`

#### 5.2.5 RecoveryState

表示恢复相关状态：

- `interruptedAt`
- `interruptReason`
- `recoveryEvent`
- `recoverySnapshotId`

---

### 5.3 Memory Layer（记忆接入层）

从你当前项目背景可知，记忆工程已独立设计，并强调可审计、可回看当时看了什么上下文 [5]。  
在本方案中，Memory 不再作为简单拼装片段，而作为独立上下文来源分层接入。

#### 5.3.1 短期记忆

服务当前会话和当前任务：

- 最近关键轮次
- 当前会话中已确认事实
- 当前任务阶段背景
- 当前未解决问题
- 当前会话中的局部语义线索

#### 5.3.2 长期记忆

服务跨会话复用：

- 用户稳定偏好
- 可长期复用业务事实
- 长期约束或惯例

#### 5.3.3 记忆接入原则

- 记忆不默认常驻
- 由 `Context Assembler` 按节点按需拉取
- 短期记忆供输入重构和摘要使用
- 长期记忆供补充偏好与稳定事实使用
- 记忆写入必须具备来源、置信度、可修正机制 [2][5]

---

## 6. Input Reconstruction Agent（输入重构 Agent）

这是本方案最重要的新增能力。

### 6.1 设计目标

不再让用户原始输入直接驱动 RAG、MCP、蓝图生成与意图判断。  
而是先基于当前会话与当前任务语境，对用户输入进行：

- 语义补全
- 实体消歧
- 任务显化
- 约束补齐
- 时间范围明确
- 任务目标重写

### 6.2 输入

- 用户原始输入
- 当前会话全量短期记忆
- 最近关键轮次
- `TaskState`
- `RetrievalState`
- `ToolState`
- 当前双摘要
- 上一轮未完成动作
- 当前阶段目标

### 6.3 输出结构

建议结构化返回：

- `normalizedUserIntent`
- `explicitTaskGoal`
- `clarifiedEntities`
- `missingSlots`
- `timeScope`
- `businessConstraints`
- `reformulatedQueryForRag`
- `reformulatedQueryForMcp`
- `blueprintHint`
- `intentConfidence`

### 6.4 使用范围

重构结果统一作为以下模块输入：

- 意图判断
- 蓝图生成
- RAG Query Builder
- MCP 候选能力检索
- 主模型当前节点工作集构建

### 6.5 设计原因

任务感知检索与决策不应只依赖用户当前一句话，而应由当前节点、已确认条件、状态与上下文共同决定 [2]。

---

## 7. RAG 集成方案

---

### 7.1 RAG 的架构定位

RAG 是知识供给层，不是状态层。  
当前 Luna 的在线链路中，RAG 请求进入 `RetrievalServiceImpl` 时不直接调用模型，统一负责编排 [4]；最终回答仍由主模型在 RAG 之后完成 [4]。  
因此，RAG 的产物应视为**候选知识证据**，而不是直接进入最终 Prompt 的原始内容。

### 7.2 RAG 新链路

#### 阶段 1：构造任务感知 Query

Query 不再直接使用用户原句，而使用 `Input Reconstruction Agent` 输出的：

- `reformulatedQueryForRag`
- `clarifiedEntities`
- `businessConstraints`
- `timeScope`
- `currentStage`

#### 阶段 2：执行现有 RAG 检索

复用 Luna 当前 RAG 编排与检索能力 [4]。

#### 阶段 3：底层 Rerank

按你的要求，agent 重排前必须先做各自 rerank：

- 每来源结果先做基础重排
- 每阶段结果先做阶段内重排
- 全局结果先做模型外全局 rerank

当前 RAG 已在规划与全局重排中使用模型能力，检索本体仍主要依赖向量库 [4]。

#### 阶段 4：Global Context Rerank Agent 二次重排

在底层 rerank 之后，再由 small agent 做**跨来源、面向当前节点的统一语义重排**。

#### 阶段 5：证据块生成

small agent 输出结构化 evidence blocks，而不是将 Top-K 文档原文直接拼入 Prompt。

### 7.3 与当前 RAG 架构的衔接

当前 RAG 的 LLM 使用重点在规划与全局重排，最终回复由主模型完成 [4]。  
因此本方案不替换现有 RAG，而是在其之后增加“面向上下文治理的全局证据提炼层”。

---

## 8. MCP 集成方案

---

### 8.1 MCP 的架构定位

当前 MCP 架构文档已明确其覆盖架构分层、运行阶段职责、调用链与数据约束 [3]。  
在上下文工程中，MCP 不只承担工具调用，还承担：

- 工具能力供给
- Prompt 资源供给
- 外部 Resource 供给
- Workflow/蓝图提示供给

### 8.2 MCP 候选检索策略

MCP 候选能力不再由用户原始输入直接驱动，而改为基于：

- `reformulatedQueryForMcp`
- `explicitTaskGoal`
- `currentStage`
- `clarifiedEntities`
- `blueprintHint`

### 8.3 MCP 候选的两级排序

#### 第一级：系统级初排

可基于：

- 资源类型匹配
- schema 匹配
- 工具名/描述匹配
- workflow 类型匹配
- 基础文本/向量相关性

#### 第二级：Global Context Rerank Agent 全局语义重排

把 MCP 候选与 RAG、Memory 候选一起纳入统一语义评估，判断：

- 哪些 MCP 能力对当前节点真正有帮助
- 哪些能力虽然相关但不应占用当前上下文预算
- 哪些 Resource / Prompt / Workflow 应被抽取为 hints

### 8.4 MCP 执行链路对接

当前 MCP 阶段中，LLM 决策负责输出目标能力名与参数 JSON [3]，随后会做参数修复与 schema 校验 [3]。  
本方案中的变化是：

- MCP 候选阶段前移到“输入重构 + 全局重排”之后
- 参数生成仍可保留现有 MCP 阶段设计 [3]
- 工具结果解释改由 `Tool Semantic Agent` 完成

---

## 9. Global Context Rerank Agent（全局上下文重排 Agent）

### 9.1 设计目标

它不是替代底层 rerank，而是站在“当前任务节点”的角度，对所有候选结果做统一语义重排。

### 9.2 输入

- RAG 底层 rerank 后结果
- MCP 初排后候选
- Memory 检索结果
- `TaskState`
- 输入重构结果
- 当前节点目标
- token budget

### 9.3 输出

- `selectedKnowledgeBlocks`
- `selectedToolCandidates`
- `selectedPromptResources`
- `selectedMemoryHints`
- `duplicateClusters`
- `rejectedCandidates`
- `rationaleByNode`

### 9.4 核心判断标准

它关注的不是简单相似度，而是：

- 是否服务当前节点
- 是否与当前状态匹配
- 是否比现有活跃证据更强
- 是否值得进入工作集
- 是否可能造成噪声或歧义 [2]

---

## 10. Tool Result Interpreter：纯 Small Agent 转译 + 结构校验

### 10.1 设计原则

工具结果通常不是为模型消费设计的，直接回灌会引入大量噪声，因此需要中间表示层 [2]。  
本方案按你的要求，采用：

- **纯 small agent 转译**
- **系统结构校验**
- **双通道写入**

### 10.2 阶段 1：原始结果落盘

MCP Tool 调用后，原始结果完整写入：

- `ToolState`
- Context Snapshot
- 日志系统

### 10.3 阶段 2：Tool Semantic Agent 转译

输入：

- `toolName`
- `toolDescription`
- `rawResult`
- `currentTaskState`
- `currentNodeGoal`

输出：

- `toolStatus`
- `keyFacts`
- `businessImpact`
- `unresolvedIssues`
- `nextStepHint`
- `confidence`

### 10.4 阶段 3：结构校验

系统只做：

- 字段完整性检查
- JSON/schema 合法性检查
- 必填信息校验
- 预算检查
- 与当前状态的显式冲突检查

### 10.5 阶段 4：双通道写入

#### Raw Channel

原始结果进入状态系统和日志系统，作为权威记录 [2]。

#### Semantic Channel

small agent 生成的语义结果进入活跃上下文，供主模型继续推理。

### 10.6 与当前链路衔接

当前系统已支持在 Agent 工具决策前写入上下文快照，并在审批场景下中断续跑 [1]。  
本方案保留该能力，只升级工具结果解释环节。

---

## 11. Summary Engine：全量短期记忆驱动的双摘要

### 11.1 设计目标

Summary Engine 不应只基于“最近若干轮消息”，而应基于当前会话的**全量短期记忆**进行摘要，并结合当前任务状态进行双摘要生成。

### 11.2 输入

- 当前会话全量短期记忆
- `TaskState`
- `RetrievalState`
- `ToolState`
- 当前活跃知识证据
- 当前活跃 MCP 资源提示
- 当前阶段与节点信息

### 11.3 输出

#### A. Narrative Summary

保留：

- 会话背景
- 用户目标演化
- 关键讨论路径
- 语义延续与表达背景

#### B. State Snapshot

保留：

- 当前阶段
- 已确认参数
- 已完成动作
- 未决事项
- 最新工具结论
- 当前限制条件
- 下一步建议

### 11.4 原则

生产中会话摘要应拆成叙事摘要与状态快照两部分，前者维持自然语义连续性，后者保证任务可控性 [2]。

### 11.5 裁剪原则

裁剪不是截断。  
裁剪只能通过以下方式完成：

- 去重
- 聚类合并
- 字段抽取
- 模板化压缩
- 摘要替换
- 结构化保留关键约束

不得因为裁剪改变任务语义、数字约束、步骤状态或未决问题 [2]。

---

## 12. Context Assembler（上下文组装器）

当前系统已有 Prompt 组装能力，但其形态更接近固定顺序拼装。  
本方案建议将其升级为独立的上下文组装器。

### 12.1 设计目标

把上下文装配从“helper 拼串逻辑”升级为“可治理的信息供应链” [2]。

### 12.2 职责

`Context Assembler` 负责：

1. 感知当前节点类型
2. 收集候选上下文池
3. 调用各 small agent 做筛选、重排、转译、摘要
4. 执行分层预算与语义保持型裁剪
5. 生成最终工作集
6. 落盘上下文快照

### 12.3 候选池来源

- System Prompt
- MCP Prompt
- 用户原始输入
- 重构后的用户任务表达
- 当前短期记忆
- 当前长期记忆
- `TaskState` / `RetrievalState` / `ToolState`
- RAG 候选与证据块
- MCP Resource/Workflow 候选
- Tool Semantic Result
- 双摘要

### 12.4 最终工作集分区

建议固定为：

1. `Instructions`
2. `Current Task State`
3. `Reconstructed User Intent`
4. `Relevant Knowledge Evidence`
5. `MCP Resource / Prompt Hints`
6. `Tool Evidence`
7. `Recent Interaction Context`
8. `Memory Hints`
9. `Output Constraints`

### 12.5 为什么要固定分区

因为成熟上下文系统必须把规则、状态、知识、工具结果、记忆分层治理，否则会快速变得不可解释、不可维护 [2]。

---

## 13. 上下文生命周期设计

上下文工程不是一次性 Prompt 构建，而是持续生成、筛选、压缩、写回与失效的循环系统 [2]。

### 13.1 生成

来源包括：

- 用户原始输入
- 会话短期记忆
- 长期记忆
- RAG 检索
- MCP 资源与工具结果
- 系统规则
- 状态系统
- 摘要系统

### 13.2 候选池构建

`Context Assembler` 从多源拉取候选上下文，建立候选池。

### 13.3 输入重构

`Input Reconstruction Agent` 输出标准化任务表达。

### 13.4 多路召回

分别执行：

- RAG Query 与检索
- MCP 候选能力 / Prompt / Resource / Workflow 检索
- Memory 候选检索

### 13.5 各自底层 Rerank

各模块先完成自身重排。

### 13.6 全局语义重排

`Global Context Rerank Agent` 对所有候选做统一语义评估。

### 13.7 工具结果转译

`Tool Semantic Agent` 处理 MCP Tool 返回结果。

### 13.8 分层组装

`Context Assembler` 将上下文组织为固定分区工作集。

### 13.9 预算与语义保持型裁剪

按层执行预算，保证语义不失真。

### 13.10 主模型执行

主模型只消费最终工作集。

### 13.11 写回

本轮结果写回：

- `State Store`
- `Memory Layer`
- `Summary Engine`
- `Snapshot Store`

### 13.12 衰减与失效

- 老旧历史进入摘要
- 过时工具结果降权
- 已完成阶段的证据退出活跃上下文
- 临时中间结论不进入长期记忆 [2]

---

## 14. 节点级执行流程

---

### 14.1 普通回复节点

1. 读取当前状态与全量短期记忆
2. 调用 `Input Reconstruction Agent`
3. 如需知识，触发 RAG 检索
4. 如需资源，触发 MCP 候选检索
5. 各自执行底层 rerank
6. `Global Context Rerank Agent` 做统一重排
7. `Context Assembler` 组装最终工作集
8. 主模型生成回复
9. 写回短期记忆与状态

---

### 14.2 蓝图 / 计划生成节点

蓝图生成不应基于用户原始输入，而应基于重构后的任务表达。

流程：

1. 读取 `normalizedUserIntent` 与 `explicitTaskGoal`
2. 结合当前 `TaskState`
3. 结合可用知识证据与 workflow hints
4. 生成任务骨架 / blueprint
5. 写回当前阶段与下一步动作

这与 Memory 文档中“Task Draft 输出任务骨架：结论、步骤、风险、所需确认、下一步”的思路一致 [5]。

---

### 14.3 RAG 增强节点

1. 使用 `reformulatedQueryForRag`
2. 进入现有 `RetrievalServiceImpl` 检索入口 [4]
3. 执行底层 rerank
4. `Global Context Rerank Agent` 生成知识证据块
5. `Context Assembler` 注入知识分区
6. 主模型完成回答

---

### 14.4 MCP 工具决策节点

1. 使用 `reformulatedQueryForMcp`
2. 检索 MCP 候选能力 / Prompt / Resource / Workflow
3. 先做系统级初排
4. 再做全局语义重排
5. `Context Assembler` 组装工具决策上下文
6. 在工具决策前写入上下文快照 [1]
7. 主模型输出能力名与参数 JSON [3]
8. 进入参数修复与 schema 校验阶段 [3]
9. 执行工具

---

### 14.5 MCP 工具结果处理节点

1. 原始结果落盘
2. `Tool Semantic Agent` 转译
3. 系统结构校验
4. 语义结果写入活跃上下文
5. 主模型决定下一步

---

### 14.6 摘要节点

1. 读取当前会话全量短期记忆
2. 结合当前状态与证据
3. `Summary Agent` 输出双摘要
4. 系统校验状态快照
5. 替换冗余历史
6. 不做粗暴截断

---

### 14.7 恢复节点

当前系统已支持工具审批中断与恢复，恢复时基于原上下文重新组装 Prompt [1]。  
本方案升级为：

1. 恢复中断前快照
2. 恢复中断前状态
3. 读取恢复事件
4. `Recovery Context Agent` 判断哪些信息失效
5. 必要时重新触发 RAG / MCP 召回
6. 重新组装恢复后的工作集
7. 主模型继续执行

---

## 15. Token 预算与语义保持型裁剪

### 15.1 预算策略

预算按层分配，而不是最后统一截断：

- `Instructions`：固定保留
- `Current Task State`：固定保留
- `Reconstructed User Intent`：固定保留
- `Tool Evidence`：高优先级
- `RAG Evidence`：弹性预算
- `MCP Resource Hints`：弹性预算
- `Recent Interaction`：摘要优先
- `Memory Hints`：按需注入

### 15.2 裁剪顺序

优先裁掉：

1. 重复知识片段
2. 重复 MCP 资源说明
3. 低价值历史聊天
4. 无关工具元数据

最后裁掉：

1. 当前状态
2. 重构后的当前任务表达
3. 当前节点关键证据
4. 最近工具语义结果

### 15.3 裁剪方法

裁剪只允许采用以下方式：

- 去重
- 聚类合并
- 字段抽取
- 模板化
- 双摘要替换
- 证据块压缩

### 15.4 明确禁止

禁止：

- 按 token 直接截断关键状态
- 按长度删除未决问题
- 丢弃最新工具结论
- 用自由摘要替代关键数值、约束、时间条件 [2]

---

## 16. 可观测性与审计设计

没有上下文可观测，就无法系统优化 Agent [2]。  
Memory 设计也强调必须能回看“为什么这么规划、为什么这么回应、当时看了什么上下文” [5]。

### 16.1 输入重构日志

记录：

- 用户原始输入
- 重构后的任务表达
- 补全了哪些缺失信息
- 消除了哪些歧义

### 16.2 多路召回日志

记录：

- RAG query
- MCP query
- Memory query
- 各自候选结果

### 16.3 双层重排日志

记录：

- 各路底层 rerank 结果
- 全局语义重排后的最终选择

### 16.4 工具结果转译日志

记录：

- 原始工具返回
- small agent 转译结果
- 结构校验结果

### 16.5 摘要日志

记录：

- 全量短期记忆输入
- `Narrative Summary`
- `State Snapshot`

### 16.6 最终上下文快照

记录：

- 实际送入主模型的完整上下文
- 各分区内容
- token 占比

### 16.7 状态演进日志

记录：

- 节点流转
- RAG 调用
- MCP 调用
- 摘要触发
- 工具执行
- 中断恢复

---

## 17. 与当前项目模块的落地映射

### 17.1 Agent Context 模块

保留当前已有能力：

- 短期会话读取
- 多源检索
- 上下文裁剪
- 工具调用上下文快照
- 审批中断恢复
- 最终 Prompt 组装 [1]

升级为：

- `Context Assembler`
- `Context Snapshot Store`
- `State-driven Context Pipeline`

### 17.2 MCP 模块

保留当前能力：

- MCP 分层架构
- LLM 决策输出能力名与参数 JSON [3]
- 参数修复与 schema 校验 [3]

新增：

- `McpQueryBuilder`
- `McpCandidatePreRank`
- `ToolSemanticAgent`
- `McpResourceHintExtractor`

### 17.3 RAG 模块

保留当前能力：

- `RetrievalServiceImpl` 在线入口 [4]
- 规划与全局重排能力 [4]
- 检索本体向量数据库
- 最终主模型回答链路 [4]

新增：

- `RagQueryBuilder`
- `GlobalContextRerankAgent`
- `EvidenceBlockBuilder`

### 17.4 Memory 模块

结合现有记忆架构：

- 短期记忆作为输入重构与摘要引擎核心输入
- 长期记忆按节点按需注入
- 记忆写入需可审计、可回放 [5]

---

## 18. 推荐实施阶段

### 18.1 第一阶段：最小侵入升级

优先实现：

1. `Input Reconstruction Agent`
2. `Tool Semantic Agent`
3. `Summary Agent`
4. `Global Context Rerank Agent`
5. `PromptAssembler -> Context Assembler` 轻量升级

### 18.2 第二阶段：全链路治理

补齐：

- 显式候选池
- 分层预算
- 统一上下文快照
- 双层重排日志
- 语义保持型裁剪器

### 18.3 第三阶段：面向操作系统化演进

进一步完善：

- 恢复型上下文重建
- 节点级上下文模板
- 证据块标准化
- 记忆写入门槛治理
- 质量评估与回放对比

---

## 19. 风险与注意事项

### 19.1 Small Agent 不能替代系统控制

small agent 负责“理解与整理”，不能负责：

- 状态最终写入裁决
- 参数 schema 最终合法性
- 工具调用最终成功判定
- 恢复流程最终跳转

### 19.2 不要让一个 Small Agent 包办所有中间任务

建议拆分专责微 agent，而不是一个统一“大中间 Agent”，否则会造成：

- 提示词复杂度过高
- 中间产物不稳定
- 可调试性下降

### 19.3 不要把所有中间推理保存为长期记忆

长期记忆应只保存高置信、可复用、可校验内容，避免污染 [2]。

### 19.4 裁剪器必须以语义保持为第一约束

任何压缩策略若改变：

- 已确认条件
- 当前阶段判断
- 关键数值/时间/范围
- 最近工具结论

都应视为不合格裁剪。

---

## 20. 结论

Luna 当前已经具备上下文采集、增强、裁剪、注入、落盘与工具链恢复的可运行闭环 [1]，MCP 与 RAG 也已具备成熟的接入基础 [3][4]。  
下一阶段的关键不再是继续叠加信息源，而是把这些信息源统一纳入**上下文治理体系**。

本方案提出的核心升级包括：

- 不再以用户原始输入直接驱动 RAG、MCP、蓝图与意图判断
- 引入 `Input Reconstruction Agent` 统一生成任务表达
- 各路结果先做底层 rerank，再做全局 small agent 语义重排
- 工具结果采用“纯 small agent 转译 + 结构校验 + 双通道写入”
- Summary Engine 基于全量短期记忆与当前状态生成双摘要
- 裁剪强调语义保持，禁止粗暴截断
- 将上下文从 Prompt 拼装升级为节点级工作集治理 [2]

最终目标是：  
让主模型始终工作在一个**高相关、低噪声、可恢复、可审计、可回放**的上下文环境中，从而把 Luna 从“可用原型”演进为“稳定可持续的生产级 Agent 系统” [1][2][5]。

---

## 21. 附录：建议新增核心类与能力清单

### 21.1 Small Agent 类

- `InputReconstructionAgent`
- `GlobalContextRerankAgent`
- `ToolSemanticAgent`
- `SummaryAgent`
- `RecoveryContextAgent`（可选）

### 21.2 Builder / Assembler 类

- `ContextAssembler`
- `RagQueryBuilder`
- `McpQueryBuilder`
- `EvidenceBlockBuilder`
- `SemanticPreservingPruner`

### 21.3 Store / State 类

- `TaskStateStore`
- `RetrievalStateStore`
- `ToolStateStore`
- `ContextSnapshotStore`

### 21.4 可观测组件

- `ContextTraceLogger`
- `RerankTraceLogger`
- `SummaryTraceLogger`
- `ToolSemanticTraceLogger`

---