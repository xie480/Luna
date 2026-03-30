# 0. 先给最终结论

你的 Agent 最适合的终局形态是：

# **双域分层记忆 + 双状态机 + 统一编排器 + 双脑响应合成**

也就是：

- **任务域**：负责复杂任务、规划、执行、反思、经验沉淀
- **关系域**：负责陪伴感、真人感、情绪接住、长期关系维护
- **统一运行时**：负责会话、状态切换、上下文编译、能力路由
- **统一输出层**：把“会做事”和“有人味”融合成最终回复

这不是“聊天系统增强版”，也不是“工作流系统加点温度”。

这是一个：

# **Relationship-aware Task Agent Runtime**

---

# 1. 产品目标与架构目标

你的系统要同时满足 4 个目标：

## 1）复杂任务能力
- 任务理解
- 约束抽取
- 分阶段规划
- 工具调用
- 多轮执行
- checkpoint / recovery
- replanning
- 报告生成

## 2）陪伴感与真人感
- 记住用户
- 语气连续
- 有关系发展
- 会接情绪
- 不冷冰冰
- 不机械

## 3）长期学习能力
- 从任务中抽经验
- 从互动中抽关系偏好
- 从失败中抽恢复策略
- 从高质量陪伴中抽有效模式

## 4）可维护、可扩展
- 后续支持多 Agent
- 支持更多 workflow / tool / skill
- 支持离线经验蒸馏
- 支持调试和审计

---

# 2. 总体架构

建议总架构如下：

```text
┌────────────────────────────────────────────┐
│                Client / App / API          │
└────────────────────┬───────────────────────┘
                     │
             ┌───────▼────────┐
             │ Event Ingress   │
             │ user/tool/sys   │
             └───────┬────────┘
                     │
        ┌────────────▼────────────────┐
        │ Session Orchestrator         │
        │ - task state machine         │
        │ - relational state machine   │
        │ - policy router              │
        └───────┬───────────┬─────────┘
                │           │
      ┌─────────▼───┐   ┌──▼────────────┐
      │ Task Context │   │ Relation Ctx  │
      │ Retriever    │   │ Retriever     │
      └──────┬───────┘   └────┬──────────┘
             │                │
             └────────┬───────┘
                      ▼
              Context Compiler
                      ▼
         ┌────────────┴────────────┐
         │ Model Router / Policy    │
         │ - planning model         │
         │ - execution model        │
         │ - social model/prompt    │
         └────────────┬────────────┘
                      ▼
            ┌────────────────────┐
            │ Response Synthesizer│
            └──────────┬─────────┘
                       │
            ┌──────────▼──────────┐
            │ Memory Write Pipeline│
            └─────┬────────┬──────┘
                  │        │
        ┌─────────▼─┐   ┌──▼─────────────┐
        │ Task Mem  │   │ Relational Mem │
        └───────────┘   └────────────────┘
```

---

# 3. 分层设计：四个层次

我建议你按 4 层理解整个系统。

---

## L1. Runtime 层
负责“现在正在发生什么”

包括：
- session
- 当前 task state
- 当前 relational state
- 当前 plan
- 当前 active node
- 最近消息
- 当前工作记忆缓存

特点：
- 高频读写
- 小而精
- 强实时性

---

## L2. Memory 层
负责“系统记住了什么”

拆成两域：

### 任务域记忆
- task_working_memory
- task_semantic_memory
- task_episodic_memory
- task_procedural_memory

### 关系域记忆
- relational_working_memory
- relational_semantic_memory
- relational_episodic_memory
- relational_procedural_memory

---

## L3. Capability 层
负责“系统能做什么”

包括：
- tools
- workflows
- prompts
- resources
- strategies

---

## L4. Learning 层
负责“系统如何越来越好”

包括：
- reflection
- procedure mining
- memory consolidation
- profile refinement
- success/failure statistics

---

# 4. 双状态机设计

这是核心中的核心。

---

## 4.1 任务状态机

建议如下：

```text
IDLE
UNDERSTANDING
CONTEXT_BUILDING
PLANNING
WAITING_PLAN_CONFIRMATION
EXECUTING
WAITING_TOOL
WAITING_USER
WAITING_APPROVAL
REFLECTING
REPLANNING
REPORTING
COMPLETED
FAILED
CANCELLED
```

### 每个状态的职责

#### IDLE
- 无活跃任务
- 可能是闲聊或陪伴

#### UNDERSTANDING
- 提炼用户目标
- 识别缺失信息
- 判断是否需要进入复杂任务流程

#### CONTEXT_BUILDING
- 拉取必要语义记忆/知识
- 建立当前任务边界

#### PLANNING
- 生成 plan / phase / node
- 估算风险
- 选择能力

#### EXECUTING
- 按节点推进
- 调工具
- 保存中间状态

#### REFLECTING
- 失败分析
- 异常归因
- 判断是否重规划

#### REPORTING
- 汇总结构化结果
- 生成可交付产物

---

## 4.2 关系状态机

建议如下：

```text
COLD_START
FAMILIARIZING
TRUST_BUILDING
COMPANION_MODE
LIGHT_CHAT
DEEP_TALK
EMOTIONAL_SUPPORT
FRAGILE_MOMENT
REPAIRING
CELEBRATING
```

### 典型场景

#### COLD_START
- 第一次见面
- 轻建立感，不宜过熟

#### TRUST_BUILDING
- 用户开始反复来找你
- 需要稳定、一致、不过界

#### EMOTIONAL_SUPPORT
- 用户明确低落、焦虑、疲惫
- 先接住，不急着做事

#### FRAGILE_MOMENT
- 敏感/脆弱场景
- 严格控制语气、追问强度、建议方式

#### REPAIRING
- 模型说错话了
- 用户感到被忽视/被冒犯
- 需要修复关系

---

## 4.3 双状态融合规则

每次事件进入，都同时求两个状态：

- `task_state`
- `relational_state`

然后决定策略。

### 示例

#### 情况 A
用户说：
> 帮我整理这周的工作计划

- task_state: UNDERSTANDING
- relational_state: LIGHT_CHAT

策略：
- 任务优先
- 保留轻微温度

#### 情况 B
用户说：
> 我今天真的撑不住了，但明天还要交方案

- task_state: UNDERSTANDING / PLANNING
- relational_state: EMOTIONAL_SUPPORT

策略：
- 先接情绪
- 再缩小任务范围
- 生成最低负担执行方案

---

# 5. 记忆体系：双域分层

你要求的是“拆成两套表族”，所以这里明确为：

---

## 5.1 任务域记忆栈

### 1）Task Perceptual Buffer
最近任务相关消息和工具结果  
存 Redis + message log

### 2）Task Working Memory
当前任务临时工作台  
主供 prompt

### 3）Task Semantic Memory
稳定工作偏好、规则、事实

### 4）Task Episodic Memory
过去任务经历、成功/失败案例

### 5）Task Procedural Memory
如何拆任务、如何选工具、如何恢复

---

## 5.2 关系域记忆栈

### 1）Relational Perceptual Buffer
最近情绪线索、最近互动氛围

### 2）Relational Working Memory
当前情绪、当前互动目标、当前推荐语气

### 3）Relational Semantic Memory
称呼偏好、边界、互动风格、支持偏好

### 4）Relational Episodic Memory
某次安慰、某次误解修复、某次高质量陪伴

### 5）Relational Procedural Memory
对这个用户怎样接情绪、怎样过渡、怎样收尾更有效

---

# 6. 数据库设计

下面给你的是**完整可用版数据分层设计**。  
不是全量 SQL 细节，但已经足够作为数据库蓝图。

---

# 6.1 统一运行域

这些表是两域共享的。

---

## 6.1.1 principal
用户主体表

字段：
- `principal_id`
- `principal_type`
- `tenant_id`
- `display_name`
- `profile_json`
- `created_at`
- `updated_at`

---

## 6.1.2 agent_identity
Agent 自身身份/人设

字段：
- `agent_id`
- `agent_name`
- `persona_name`
- `persona_desc`
- `default_tone`
- `config_json`

---

## 6.1.3 agent_session
统一会话容器

字段：
- `session_id`
- `principal_id`
- `agent_id`
- `session_type`：TASK / COMPANION / HYBRID
- `task_state`
- `relational_state`
- `current_plan_id`
- `current_goal`
- `last_user_message_at`
- `last_agent_message_at`
- `metadata_json`

---

## 6.1.4 state_transition_log
记录状态切换

字段：
- `id`
- `session_id`
- `state_domain`：TASK / RELATION
- `from_state`
- `to_state`
- `trigger_type`
- `trigger_ref`
- `reason`
- `payload_json`
- `created_at`

---

## 6.1.5 conversation_message
统一消息流

字段：
- `message_id`
- `session_id`
- `plan_id`
- `role`
- `message_type`
- `content_text`
- `content_json`
- `trace_id`
- `created_at`

---

## 6.1.6 event_inbox（建议新增）
统一事件入口

字段：
- `event_id`
- `session_id`
- `event_type`：USER_INPUT / TOOL_RESULT / APPROVAL / SYSTEM / TIMER
- `payload_json`
- `status`
- `trace_id`
- `created_at`

作用：
- 让请求、工具回调、定时事件统一进入 runtime

---

# 6.2 任务域表族

---

## 6.2.1 task_working_memory
当前任务工作台

字段：
- `twm_id`
- `session_id`
- `principal_id`
- `plan_id`
- `goal_raw`
- `goal_refined`
- `intent_json`
- `constraints_json`
- `success_criteria_json`
- `assumptions_json`
- `key_entities_json`
- `key_facts_json`
- `unresolved_questions_json`
- `risks_json`
- `active_phase_id`
- `active_node_id`
- `recent_tool_outputs_json`
- `local_scratchpad`
- `version`
- `updated_at`

用途：
- prompt 主上下文
- 任务执行时的真工作台

---

## 6.2.2 task_working_memory_slot
任务工作记忆的精细槽位

字段：
- `id`
- `twm_id`
- `slot_name`
- `slot_type`
- `slot_value_json`
- `priority`
- `freshness_score`
- `source_type`
- `source_ref`
- `updated_at`

用途：
- 避免整块覆盖
- 精准更新和编排

---

## 6.2.3 task_semantic_fact
任务语义记忆

字段：
- `fact_id`
- `principal_id`
- `scope_type`：USER / SESSION / PLAN / GLOBAL
- `fact_type`：PREFERENCE / PROFILE / RULE / CONSTRAINT / DOMAIN_FACT
- `fact_key`
- `fact_value_text`
- `fact_value_json`
- `description`
- `confidence_score`
- `stability_score`
- `source_type`
- `source_ref`
- `valid_from`
- `valid_to`
- `last_confirmed_at`
- `embedding`
- `deleted`
- `created_at`
- `updated_at`

例子：
- 用户喜欢表格输出
- 用户工作领域是 SaaS
- 该用户做市场分析默认偏中文资料

---

## 6.2.4 knowledge_document
知识文档主表

字段：
- `doc_id`
- `owner_scope`
- `owner_ref`
- `source_type`
- `source_uri`
- `title`
- `metadata_json`
- `created_at`

---

## 6.2.5 knowledge_chunk
知识分片表

字段：
- `chunk_id`
- `doc_id`
- `chunk_order`
- `chunk_text`
- `chunk_summary`
- `keywords_json`
- `embedding`
- `tsv`
- `metadata_json`
- `created_at`

---

## 6.2.6 task_episode
任务情景记忆

字段：
- `episode_id`
- `principal_id`
- `session_id`
- `plan_id`
- `episode_type`：SUCCESS / FAILURE / DECISION / PARTIAL
- `title`
- `task_goal`
- `context_json`
- `trajectory_summary`
- `outcome_summary`
- `outcome_status`
- `lessons_learned`
- `importance_score`
- `reusability_score`
- `embedding`
- `created_at`

---

## 6.2.7 task_episode_step
任务 episode 分步骤

字段：
- `id`
- `episode_id`
- `step_order`
- `step_type`
- `title`
- `content_text`
- `payload_json`
- `created_at`

---

## 6.2.8 task_procedure_pattern
任务程序性记忆

字段：
- `procedure_id`
- `procedure_type`：PLANNING_PATTERN / TOOL_CHAIN / RECOVERY / VALIDATION
- `name`
- `description`
- `trigger_conditions_json`
- `applicability_scope_json`
- `pattern_steps_json`
- `success_signals_json`
- `failure_signals_json`
- `source_kind`
- `confidence_score`
- `usage_count`
- `success_count`
- `fail_count`
- `embedding`
- `created_at`
- `updated_at`

---

## 6.2.9 task_reflection_record
任务反思记录

字段：
- `reflection_id`
- `plan_id`
- `node_id`
- `reflection_type`
- `trigger_reason`
- `observation`
- `root_cause`
- `proposed_fix`
- `extracted_pattern_json`
- `quality_score`
- `created_at`

---

# 6.3 关系域表族

---

## 6.3.1 relational_working_memory
当前关系工作台

字段：
- `rwm_id`
- `session_id`
- `principal_id`
- `current_relational_state`
- `inferred_emotion`
- `emotion_confidence`
- `desired_tone`
- `support_intent`
- `interaction_goal`
- `caution_flags_json`
- `recent_bond_signals_json`
- `recent_sensitive_signals_json`
- `updated_at`

用途：
- 当前这一轮怎么回更像真人、更有分寸

---

## 6.3.2 relational_profile
长期关系画像

字段：
- `profile_id`
- `principal_id`
- `relationship_stage`
- `preferred_name`
- `preferred_tone`
- `emotional_support_style`
- `humor_preference`
- `intimacy_preference`
- `interaction_style_json`
- `boundary_preferences_json`
- `sensitive_topics_json`
- `comfort_triggers_json`
- `no_go_patterns_json`
- `trust_score`
- `intimacy_score`
- `created_at`
- `updated_at`

---

## 6.3.3 relational_semantic_fact
关系语义记忆

字段：
- `fact_id`
- `principal_id`
- `fact_type`：ADDRESS_PREFERENCE / SUPPORT_STYLE / BOUNDARY / SENSITIVE_TOPIC / INTERACTION_STYLE
- `fact_key`
- `fact_value_text`
- `fact_value_json`
- `description`
- `confidence_score`
- `stability_score`
- `source_type`
- `source_ref`
- `valid_from`
- `valid_to`
- `last_confirmed_at`
- `embedding`
- `deleted`
- `created_at`
- `updated_at`

---

## 6.3.4 emotional_baseline
情绪基线

字段：
- `id`
- `principal_id`
- `usual_expression_style`
- `stress_signals_json`
- `burnout_signals_json`
- `sadness_signals_json`
- `comfort_preferences_json`
- `encouragement_patterns_json`
- `escalation_threshold`
- `updated_at`

---

## 6.3.5 relational_episode
关系情景记忆

字段：
- `episode_id`
- `principal_id`
- `session_id`
- `episode_type`：COMFORT / BONDING / REPAIR / CELEBRATION / DISCLOSURE
- `title`
- `summary`
- `emotion_before`
- `emotion_after`
- `trigger_json`
- `support_style_used`
- `interaction_quality`
- `response_effectiveness`
- `embedding`
- `created_at`

---

## 6.3.6 relational_procedure_pattern
关系程序性记忆

字段：
- `procedure_id`
- `procedure_type`：COMFORT_PATTERN / REPAIR_PATTERN / LIGHT_CHAT_PATTERN / TRANSITION_PATTERN
- `name`
- `description`
- `trigger_conditions_json`
- `applicability_scope_json`
- `pattern_steps_json`
- `success_signals_json`
- `failure_signals_json`
- `source_kind`
- `confidence_score`
- `usage_count`
- `success_count`
- `fail_count`
- `embedding`
- `created_at`
- `updated_at`

---

## 6.3.7 relational_reflection_record
关系反思

字段：
- `reflection_id`
- `session_id`
- `reflection_type`：SUPPORT_REVIEW / MISALIGNMENT / REPAIR_ANALYSIS
- `trigger_reason`
- `observation`
- `root_cause`
- `proposed_fix`
- `extracted_pattern_json`
- `quality_score`
- `created_at`

---

## 6.3.8 relational_boundary_rule
关系边界规则

字段：
- `id`
- `principal_id`
- `rule_type`：ADDRESS / PRIVACY / TOPIC / EMOTIONAL / PACE
- `rule_key`
- `rule_value`
- `confidence_score`
- `source_type`
- `updated_at`
- `created_at`

---

# 6.4 共享元记忆域

这个域不拆，因为它就是跨域管理层。

---

## 6.4.1 memory_registry
统一记忆登记

字段：
- `memory_id`
- `memory_domain`：TASK / RELATION
- `memory_layer`：WORKING / SEMANTIC / EPISODIC / PROCEDURAL
- `ref_table`
- `ref_id`
- `principal_id`
- `source_type`
- `source_ref`
- `confidence_score`
- `importance_score`
- `freshness_score`
- `access_count`
- `last_accessed_at`
- `archived`
- `created_at`

---

## 6.4.2 memory_relation
记忆关系表

字段：
- `id`
- `from_memory_id`
- `to_memory_id`
- `relation_type`：SUPPORTS / CONTRADICTS / DERIVED_FROM / SUMMARIZES / GENERALIZES
- `weight`
- `created_at`

用途：
- 解决冲突
- 支持来源追溯
- 支持摘要可追溯

---

# 6.5 能力层

---

## capability_registry
统一能力注册

字段：
- `capability_id`
- `capability_type`：TOOL / WORKFLOW / PROMPT / RESOURCE / STRATEGY
- `server_code`
- `capability_name`
- `title`
- `description`
- `input_schema`
- `output_schema`
- `metadata_json`
- `requires_approval`
- `sensitivity`
- `enabled`
- `version`
- `embedding`

---

# 6.6 任务执行层

你现有 `plan_*` 这套保留，并增强。

保留：
- `plan_instance`
- `plan_blueprint`
- `plan_phase`
- `plan_node`
- `plan_edge`
- `plan_event_log`
- `plan_checkpoint`
- `plan_report`

新增：

## plan_context_snapshot
记录当次 prompt context

## plan_decision_record
记录为什么这样选

## tool_execution_trace
记录工具调用输入输出标准化结果

---

# 7. 存储技术选型

---

## PostgreSQL
作为唯一事实源：
- runtime
- memory
- plan
- capability
- audit

---

## pgvector
只做语义索引，不做唯一入口：

向量索引分 5 套：
- task_semantic_fact
- task_episode
- task_procedure_pattern
- relational_episode
- relational_procedure_pattern
- knowledge_chunk

---

## Redis
只做热层：
- session cache
- recent messages
- current working memory cache
- current compiled context cache
- locks
- dedupe
- pending tool call

### 不再作为“短期记忆真相源”

---

# 8. 检索架构

不要一个总检索器。  
要分成三类。

---

## 8.1 TaskMemoryRetriever

负责查：
- task_working_memory
- task_semantic_fact
- task_episode
- task_procedure_pattern
- knowledge_chunk
- current plan/node

---

## 8.2 RelationalMemoryRetriever

负责查：
- relational_working_memory
- relational_profile
- relational_semantic_fact
- relational_episode
- relational_procedure_pattern
- emotional_baseline
- relational_boundary_rule

---

## 8.3 RuntimeRetriever

负责查：
- agent_session
- state
- recent messages
- active tool results
- context snapshot

---

# 9. Context Compiler

这是系统的关键模块。  
它不直接“拼字符串”，而是先编译成 structured context package。

---

## 9.1 输入

- session state
- task state
- relational state
- event
- task retriever result
- relation retriever result
- capability hints

---

## 9.2 输出

```json
{
  "runtime": {},
  "task_context": {},
  "relational_context": {},
  "recent_messages": [],
  "capability_candidates": [],
  "prompt_policy": {},
  "token_budget_plan": {}
}
```

---

## 9.3 Token 预算策略

例如 12k token：

### 任务重场景
- runtime: 800
- task working: 2500
- plan/node: 2000
- task facts: 1000
- task procedures: 1200
- task episodes: 1200
- knowledge: 2500
- relation context: 500
- recent messages: 300

### 关系重场景
- runtime: 500
- relational working: 1800
- profile + semantic: 1200
- relational episodes: 1500
- relational procedures: 1000
- recent messages: 1500
- task context: 500
- spare: 1000

---

# 10. 模型与 Prompt 策略

---

## 10.1 模型角色拆分

至少逻辑上分成 3 种模式：

### 1）Task Planner
负责理解/规划/replan

### 2）Task Executor
负责节点执行/总结/验证

### 3）Social Reasoner
负责情绪识别/关系态判断/表达调音

你可以实际用同一模型，但逻辑上必须分开。

---

## 10.2 Prompt 模板族

### 任务模板
- understanding_prompt
- planning_prompt
- execution_prompt
- reflection_prompt
- reporting_prompt

### 关系模板
- companion_prompt
- emotional_support_prompt
- repair_prompt
- celebration_prompt
- light_chat_prompt

### 混合模板
- task_with_empathy_prompt
- task_failure_with_support_prompt
- clarify_with_warmth_prompt

---

# 11. 响应生成：双脑融合

这是“真人感 + 任务能力”同时成立的关键。

---

## 11.1 三阶段生成

### 阶段 1：Task Draft
输出任务骨架：
- 结论
- 步骤
- 风险
- 所需确认
- 下一步

### 阶段 2：Relational Draft
输出表达策略：
- 用什么语气
- 是否先共情
- 是否鼓励
- 是否更轻松
- 如何称呼
- 如何收尾

### 阶段 3：Synthesis
融合成最终回复：
- 信息不丢
- 不冷
- 不做作
- 有连续感

---

## 11.2 示例

用户说：
> 我真的很烦，但这个月度复盘还没做。

最终输出不应该只是：
> 建议你先完成月度复盘，以下是结构...

而应该类似：
> 听起来你现在已经很烦很累了，硬逼自己一下子把整份复盘做完，反而更容易卡住。  
> 我们可以先把它缩成最小版本：只整理这个月的 3 个结果、2 个问题、1 个下月重点。  
> 如果你愿意，我现在就陪你把这个骨架搭出来，先不用追求写得完整。

---

# 12. Memory Write Pipeline

这是你当前系统最缺的。

---

## 12.1 Pipeline 步骤

每次交互/执行完成后：

1. Message Writer
2. State Updater
3. Task Working Memory Updater
4. Relational Working Memory Updater
5. Semantic Extractor
6. Episode Builder
7. Reflection Trigger
8. Procedure Miner
9. Registry Updater

---

## 12.2 写入规则

### 写入 task_semantic_fact
当出现稳定工作偏好/业务事实：
- “以后默认用 markdown 表格”
- “我做 B 端产品”
- “竞品分析默认看国内市场”

### 写入 relational_semantic_fact
当出现关系性偏好/边界：
- “别叫我全名”
- “我不喜欢太说教”
- “我难受的时候先别给方案”

### 写入 task_working_memory
当只对当前任务生效：
- “这次只看 Q1”
- “先不做结论”
- “重点是留存，不是营收”

### 写入 relational_working_memory
当只对当前轮互动有效：
- 当前情绪低落
- 当前更适合倾听
- 当前不适合过度追问

### 写入 task_episode
任务完成/失败后

### 写入 relational_episode
高质量安慰/修复/庆祝后

### 写入 procedure_pattern
当反思确认某模式具有可复用价值时

---

# 13. Reflection 与 Learning

---

## 13.1 Task Reflection 触发时机
- plan failed
- node failed
- multiple retries
- user dissatisfied
- task success but cost too high

输出：
- 根因
- 修复建议
- 是否抽 procedure

---

## 13.2 Relation Reflection 触发时机
- 用户明显冷淡
- 用户说“你没懂我”
- 用户表达不适
- 某次支持非常有效

输出：
- 哪种表达没接住
- 哪种方式有效
- 是否更新 relation procedure/profile

---

## 13.3 离线学习任务

每天/每周跑：

- 低质量 memory 清洗
- 重复 fact 合并
- 高频成功任务抽 pattern
- 高频成功安慰抽 relation pattern
- 工具成功率统计
- 支持风格效果统计
- trust / intimacy 曲线校正

---

# 14. 工程模块拆分建议

建议拆成以下服务/模块：

---

## 14.1 Runtime Service
- session 管理
- 状态机
- event dispatch

## 14.2 Task Memory Service
- task memory CRUD
- task retrieval
- task consolidation

## 14.3 Relational Memory Service
- relation memory CRUD
- emotional inference
- relation retrieval

## 14.4 Context Compiler Service
- 输入路由
- token budget
- context package

## 14.5 Planner / Executor Service
- planning
- execution
- checkpoint
- replan

## 14.6 Social Reasoner Service
- 关系态识别
- 情绪推断
- 语气建议
- support intent

## 14.7 Response Synthesizer
- task draft + social draft 融合

## 14.8 Reflection & Learning Service
- task reflection
- relation reflection
- procedure mining

## 14.9 Capability Router
- tool / workflow / resource / prompt 调度

---

# 15. 典型流程

---

## 15.1 复杂任务请求流程

```text
用户输入
→ Event Ingress
→ Session Orchestrator
→ task_state = UNDERSTANDING
→ relational_state = LIGHT_CHAT or TRUST_BUILDING
→ TaskMemoryRetriever
→ Context Compiler
→ Planning Prompt
→ plan_instance / blueprint / nodes
→ 执行节点
→ tool_execution_trace
→ 更新 task_working_memory
→ 任务完成
→ task_episode + task_reflection + task_procedure mining
→ 输出最终结果（带适度人味）
```

---

## 15.2 情绪支持流程

```text
用户输入“我今天真的很累”
→ task_state = IDLE or UNDERSTANDING
→ relational_state = EMOTIONAL_SUPPORT
→ RelationalMemoryRetriever
→ 读取 profile / baseline / relation episodes
→ emotional_support_prompt
→ social draft
→ 输出陪伴式回复
→ relational_episode
→ 更新 relational_working_memory / profile
```

---

## 15.3 混合场景流程

```text
用户输入“我很焦虑，明天还要交汇报”
→ task_state = UNDERSTANDING
→ relational_state = FRAGILE_MOMENT
→ 双检索
→ Context Compiler 做混合装配
→ Task Brain 生成最小可执行方案
→ Social Brain 先接情绪再调音
→ Response Synthesizer
→ 输出：先安抚 + 再拆任务
→ 写 task_working_memory + relational_working_memory
```

---

# 16. 迁移方案

如果你从当前系统迁移，我建议分 4 期。

---

## Phase 1：先重建 Runtime + 双 Working Memory
上线：
- agent_session
- state_transition_log
- conversation_message
- task_working_memory
- relational_working_memory
- Context Compiler 雏形

收益最大。

---

## Phase 2：重建双域长期记忆
上线：
- task_semantic_fact
- relational_profile
- relational_semantic_fact
- emotional_baseline
- knowledge_document
- knowledge_chunk

迁移：
- `user_preference` -> `task_semantic_fact` / `relational_semantic_fact`
- `knowledge_base` -> `knowledge_document` + `knowledge_chunk`

---

## Phase 3：重建 episodic / procedural / reflection
上线：
- task_episode
- relational_episode
- task_reflection_record
- relational_reflection_record
- task_procedure_pattern
- relational_procedure_pattern

---

## Phase 4：做离线学习和策略优化
上线：
- fact merge
- procedure ranking
- support style ranking
- memory cleanup
- relation quality scoring

---

# 17. 你现有表的处理建议

---

## 保留
建议继续使用：
- `plan_instance`
- `plan_blueprint`
- `plan_phase`
- `plan_node`
- `plan_edge`
- `plan_event_log`
- `plan_checkpoint`
- `plan_report`
- `mcp_server_registry`
- `mcp_tool_catalog`
- `mcp_prompt_catalog`
- `mcp_resource_catalog`
- `workflow_template`

---

## 逐步废弃
建议迁移后下线：
- `luna_memory`
- `user_preference`
- `knowledge_base`
- `mcp_tools`
- `mcp_skills`

原因：
- 太泛
- 任务与关系未分域
- 无法支撑长期策略沉淀

---

# 18. 这套方案最关键的 10 条设计纪律

最后给你最重要的工程纪律。

---

## 1. 任务域和关系域必须物理拆表
不要再合一。

## 2. Session 统一，但 Memory 分域
统一编排，不统一存储。

## 3. Working memory first
prompt 主输入必须是 working memory，不是聊天历史。

## 4. 默认不查向量库
先看 runtime 和 working memory。

## 5. 状态机决定记忆装载
不是来一条消息就一套 prompt。

## 6. 任务输出和关系表达解耦
先有内容骨架，再有表达调音。

## 7. 反思必须产出可复用 pattern
否则经验无法沉淀。

## 8. 长期记忆写入要谨慎
尤其关系域，避免一次情绪永久标签化。

## 9. Redis 只是热层，不是真相源
真相源必须在 PG。

## 10. 可审计
必须能回看：
- 为什么这么规划
- 为什么这么回应
- 当时看了什么上下文

---

# 19. 最终一句话总结

你的完整可用架构方案应该是：

# **以统一会话运行时为核心，使用任务域与关系域两套分层记忆表族，配合双状态机、上下文编译器、任务脑与关系脑双通道生成，并通过反思与程序性记忆持续学习的 Agent Runtime。**

---

如果你愿意，下一步我可以继续直接给你两种最实用的落地资料之一：

### A. **完整 PostgreSQL DDL 终稿**
把上面所有关键表直接写成可执行 SQL。

### B. **后端工程实现设计稿**
包括：
- Java/Spring 模块划分
- 服务接口定义
- 一次请求的时序图
- Context Compiler 输入输出 DTO
- Memory write pipeline 的实现伪代码