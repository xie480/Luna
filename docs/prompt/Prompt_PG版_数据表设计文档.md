# Prompt_PG版_数据表设计文档.md

## 1. 文档目标

本文档用于为当前项目的 **PG 版 Prompt 分类治理架构** 设计一套可落地的数据表方案。  
设计目标是在**不修改现有 `context / map / memory / rag` 职责边界**、不破坏当前“**编排驱动 + 分层注入 + 结构化约束**”主链路的前提下，为 Prompt 资产提供：

- 统一真源
- 分类存储
- 条目版本化
- 内容型 / 执行型分治
- 关键词匹配支持
- 装配模式支持
- 策略包支持
- 快照引用支持
- 前端热修改与回滚支持 [2]

同时，该设计遵循 Prompt 工程的方法论：  
Prompt 应被当作“配置与代码之间的中间层”管理，并最终走向系统工程；Prompt 负责表达策略，程序负责表达规则 [1]。

---

## 2. 设计原则

## 2.1 单一真源原则

Prompt 不再散落于代码常量、Agent 局部模板和前端配置中，而是统一以 PostgreSQL 为真源。  
运行时通过 Prompt Registry 读取当前生效版本，供 `DefaultContextAssembler`、各 `Default*Agent` 和主模型执行链路消费 [2]。

---

## 2.2 条目与版本分离原则

Prompt 条目本身是“身份与元数据容器”，Prompt 内容和历史演进应独立版本化。  
原因：

- 同一个 Prompt key 会持续演进
- 需要支持回滚
- 需要支持快照记录“本次实际用的是哪个版本”
- 需要区分条目元信息与版本内容

因此，设计采用：

- 条目主表
- 条目版本表

---

## 2.3 内容型与执行型分治原则

Prompt 资产不是同质的。根据当前项目实际结构，Prompt 包括 [2]：

- 人设、场景、风格、语料等内容型 Prompt
- 输入重构、工具决策、repair、summary、RAG planner 等执行型 Prompt

两者在治理上必须区分：

### 内容型 Prompt
- 允许新增
- 允许删除
- 支持关键词匹配
- 更偏酒馆内容资产

### 执行型 Prompt
- 不允许任意新增/删除
- 通常含模板变量
- 不参与关键词匹配
- 主要按 Agent / node / taskState 装配 [2]

因此，表结构中必须显式支持：
- `has_template_variables`
- `keyword_match_enabled`
- `assembly_mode`
- `edit_policy`

---

## 2.4 前端管理视图与运行时装配视图分离原则

前端以“酒馆式分类 + key/value 条目”方式管理 Prompt；  
运行时则由 `Prompt Resolver` 将条目映射到：

- `runtimeSlot`
- Agent 位点
- `DefaultContextAssembler` 的 section

因此数据表需要同时支撑两种视图：

### 前端视图
- category
- key
- value
- 关键词
- 是否可删除
- 匹配模式

### 运行时视图
- runtime_slot
- assembly_mode
- match_scope
- current_version
- enabled

---

## 2.5 快照可回放原则

当前系统已经具备 `FINAL_MODEL_CONTEXT` 快照审计能力，但缺少 Prompt 模板级 ID / version 治理 [2]。  
因此数据表设计应支持在快照中稳定引用：

- prompt_item_id
- prompt_key
- prompt_version_id
- prompt_version
- prompt_policy_id

以便支持：

- 模板级回放
- 版本比对
- 问题定位
- 条目回滚分析

---

## 3. 数据模型总览

建议最少设计以下 6 类核心表：

1. `prompt_category`：分类定义表
2. `prompt_item`：Prompt 条目主表
3. `prompt_item_version`：Prompt 条目版本表
4. `prompt_policy`：Prompt 策略包主表
5. `prompt_policy_version`：Prompt 策略包版本表
6. `prompt_runtime_snapshot_ref`：运行时快照引用表

可选扩展表：

7. `prompt_match_log`：Prompt 匹配日志表
8. `prompt_change_log`：Prompt 变更日志表
9. `prompt_tag_relation`：Prompt 标签关系表

对于个人应用来说，前 6 张表已足够，后 3 张按需添加。

---

## 4. 核心表设计

---

## 4.1 `prompt_category`

### 4.1.1 作用

用于维护 Prompt 的分类定义，支撑：

- 前端分类展示
- 分类排序
- 分类说明
- 分类是否允许关键词匹配
- 分类是否默认属于执行型

虽然分类也可以直接写死在代码里，但如果希望前端展示更统一，建议仍保留此表。

---

### 4.1.2 典型字段

- `id`
- `category_key`
- `category_name`
- `parent_category_key`
- `description`
- `sort_order`
- `keyword_match_allowed`
- `is_execution_category`
- `enabled`
- `created_at`
- `updated_at`

---

### 4.1.3 字段说明

#### `category_key`
分类唯一标识，例如：

- `persona`
- `scene`
- `corpus`
- `tool`
- `repair`
- `agent-local`

#### `parent_category_key`
支持二级分类体系，例如：

- 一级：`persona`
- 二级：`maid`

#### `keyword_match_allowed`
用于做全局限制。即使某条目自己开了关键词匹配，只要分类不允许，也不能进入关键词匹配池。

#### `is_execution_category`
用于快速区分该分类默认是：
- 内容型分类
- 执行型分类

---

### 4.1.4 推荐分类数据

建议内置如下分类：

- `system`
- `persona`
- `scene`
- `corpus`
- `style`
- `worldview`
- `relation`
- `task`
- `memory-hint`
- `rag-hint`
- `tool`
- `format`
- `repair`
- `summary`
- `guardrail`
- `agent-local`

这与当前项目 Prompt 资产的真实分布是兼容的 [2]。

---

## 4.2 `prompt_item`

### 4.2.1 作用

Prompt 条目主表，负责承载 Prompt 的“身份”和“稳定元信息”，不直接承载每次版本内容。

它代表：

- 这个 Prompt 是谁
- 属于什么分类
- 用在什么 runtimeSlot
- 是否含模板变量
- 是否支持关键词匹配
- 当前生效版本是哪一个

---

### 4.2.2 典型字段

- `id`
- `category_key`
- `sub_category`
- `prompt_key`
- `prompt_name`
- `runtime_slot`
- `has_template_variables`
- `keyword_match_enabled`
- `assembly_mode`
- `enabled`
- `priority`
- `status`
- `current_version_id`
- `is_builtin`
- `description`
- `created_at`
- `updated_at`

---

### 4.2.3 字段说明

#### `prompt_key`
Prompt 条目唯一 key，例如：

- 主 key：`persona.maid.gentle_v1`（兼容别名：`maid_gentle_v1` / `persona.maid_gentle_v1`）
- 主 key：`repair.main.json_v1`（兼容别名：`repair_main_json_v1`）
- 主 key：`agent-local.reconstruction.default_v1`（兼容别名：`reconstruction_default_v1` / `agent.reconstruction.default_v1`）

这是前端展示和运行时引用的核心字段。

#### `runtime_slot`
运行时插入位点，用于映射到当前 `DefaultContextAssembler` 的 section 或 Agent 位点 [2]。  
例如：

- `instructions.system`
- `instructions.persona`
- `memory.hints`
- `output.constraints`
- `agent.reconstruction`
- `repair.main`

#### `has_template_variables`
表示该条目是否包含与代码装配结构相关的模板变量。  
这是当前架构最重要的保护字段之一。

- `false`：通常是内容型 Prompt
- `true`：通常是执行型 Prompt

这符合“用程序表达规则”的边界 [1]。

#### `keyword_match_enabled`
是否允许该条目进入关键词匹配池。  
注意：仅条目和分类都允许时，才能真正参与关键词匹配。

#### `assembly_mode`
表示该条目的装配方式。建议值包括：

- `ALWAYS`
- `KEYWORD_ONLY`
- `AGENT_ONLY`
- `KEYWORD_AND_AGENT`
- `KEYWORD_OR_AGENT`
- `POLICY_ONLY`
- `MANUAL_ONLY`
- `DISABLED`

#### `priority`
用于 Resolver 做去重和排序。

#### `current_version_id`
指向当前 active 版本。

#### `is_builtin`
标识该条目是否由系统内置模板注册而来。  
用于兼容当前 `PromptTemplates` 和各 `Default*Agent` 初始导入场景 [2]。

---

### 4.2.4 为什么主表不直接存 `value`

因为需要支持：

- 历史版本
- 生效版本切换
- 回滚
- 快照精确引用

所以建议内容与版本分离。

---

## 4.3 `prompt_item_version`

### 4.3.1 作用

存储 Prompt 条目的每一个具体版本内容。

该表是真正承载 Prompt 文本和匹配配置的地方。

---

### 4.3.2 典型字段

- `id`
- `prompt_item_id`
- `version_no`
- `version_label`
- `prompt_value`
- `template_variables`
- `match_keywords`
- `match_scope`
- `edit_policy`
- `status`
- `change_note`
- `is_active`
- `created_at`

---

### 4.3.3 字段说明

#### `prompt_item_id`
关联到 `prompt_item`。

#### `version_no`
版本号，建议使用语义化格式：

- `1.0.0`
- `1.1.0`
- `2.0.0`

#### `version_label`
用于前端展示，可选。  
例如：

- `温柔女仆-增强语气版`
- `工具决策-保守策略版`

#### `prompt_value`
Prompt 实际内容，即前端所看到的 value。

#### `template_variables`
建议采用结构化字段保存模板变量列表。  
例如：

```json
["normalizedIntent", "missingSlots", "invalidJson"]
```

#### `match_keywords`
建议采用结构化字段保存关键词数组。  
例如：

```json
["温柔", "女仆", "陪伴", "照顾"]
```

#### `match_scope`
建议保存装配范围。  
例如：

```json
{
  "agents": ["MAIN_CHAT_AGENT"],
  "nodeKinds": ["CHAT_TURN"],
  "taskStates": ["executing", "waiting"],
  "modelFamilies": ["qwen"]
}
```

#### `edit_policy`
表示结构性可操作规则，不是权限。  
例如：

```json
{
  "create": true,
  "update": true,
  "delete": true
}
```

#### `status`
版本状态，建议仅保留：

- `draft`
- `active`
- `archived`

符合个人应用的轻量设计。

#### `is_active`
是否当前生效。  
也可以只依赖 `prompt_item.current_version_id`，但保留该字段便于查询。

#### `change_note`
修改说明，便于回溯这次版本变更是做了什么。

---

### 4.3.4 为什么将关键词、模板变量、匹配范围放在版本表

因为这些字段会随版本变化：

- 文案改了，关键词可能变化
- 模板变量可能变化
- 匹配范围可能变化
- 删除限制可能变化

所以它们应和版本内容放在一起。

---

## 4.4 `prompt_policy`

### 4.4.1 作用

用于定义 Prompt 策略包，即一组稳定的 Prompt 组合基线。  
它适用于：

- 酒馆默认聊天策略
- 特定角色默认配置
- 特定场景预设
- 特定模式下的全局规则组合

---

### 4.4.2 典型字段

- `id`
- `policy_key`
- `policy_name`
- `description`
- `enabled`
- `current_version_id`
- `created_at`
- `updated_at`

---

### 4.4.3 典型用法

例如一个策略包：

- 永远包含 `system.base_v1`
- 永远包含 `guardrail.safe.chat_v1`（别名：`guardrail.safe_chat_v1`）
- 永远包含 `format.chat.json_v2`（别名：`format.chat_json_v2`）
- 永远包含 `memory-hint.default_v1`

这样运行时不必每次都从零拼长期稳定规则。

---

## 4.5 `prompt_policy_version`

### 4.5.1 作用

存储策略包版本内容。

---

### 4.5.2 典型字段

- `id`
- `prompt_policy_id`
- `version_no`
- `include_prompt_keys`
- `exclude_prompt_keys`
- `status`
- `change_note`
- `is_active`
- `created_at`

---

### 4.5.3 字段说明

#### `include_prompt_keys`
策略包固定包含的 Prompt key 集合。  
例如：

```json
[
  "system.base_v1",
  "guardrail.safe.chat_v1",
  "format.chat.json_v2",
  "memory-hint.default_v1"
]
```

#### `exclude_prompt_keys`
用于显式排除某些 Prompt，便于策略微调。

---

## 4.6 `prompt_runtime_snapshot_ref`

### 4.6.1 作用

该表用于记录每次运行时快照实际引用了哪些 Prompt 条目与版本。  
这是对现有 `FINAL_MODEL_CONTEXT` 快照能力的补强 [2]。

---

### 4.6.2 设计意义

当前系统已有 Prompt 快照，但缺少模板级 version 治理 [2]。  
增加此表后，可以支持：

- 本次请求用了哪些 Prompt
- 每个 Prompt 的版本号是什么
- 使用了哪个策略包
- 哪些条目因关键词命中
- 哪些条目因 Agent 匹配命中

---

### 4.6.3 典型字段

- `id`
- `session_id`
- `round_id`
- `snapshot_id`
- `prompt_item_id`
- `prompt_item_version_id`
- `prompt_key`
- `prompt_version_no`
- `policy_id`
- `runtime_slot`
- `match_reason`
- `created_at`

---

### 4.6.4 `match_reason` 示例

- `ALWAYS`
- `KEYWORD_ONLY`
- `AGENT_ONLY`
- `POLICY_ONLY`
- `KEYWORD_AND_AGENT`

这个字段对问题排查非常有用。

---

## 5. 可选扩展表设计

---

## 5.1 `prompt_change_log`

### 作用

记录每次条目层面的操作事件：

- 创建
- 更新
- 删除
- 激活版本
- 回滚版本

对于个人应用不是必须，但有助于调试。

---

### 典型字段

- `id`
- `prompt_item_id`
- `action_type`
- `from_version_id`
- `to_version_id`
- `operation_note`
- `created_at`

---

## 5.2 `prompt_match_log`

### 作用

记录运行时 Resolver 的匹配结果。  
若你后续想分析：

- 哪些关键词经常命中
- 哪些 Prompt 很少被使用
- 哪些场景命中了错误人设

这个表会很有帮助。

---

### 典型字段

- `id`
- `session_id`
- `round_id`
- `user_input`
- `matched_prompt_keys`
- `match_detail`
- `created_at`

---

## 5.3 `prompt_tag_relation`

### 作用

如果后续 Prompt 数量非常多，除了关键词匹配外，可能还需要标签检索。  
当前阶段可以不做，先用 `match_keywords` 即可。

---

## 6. 数据表关系设计

建议核心关系如下：

```text
prompt_category 1 --- n prompt_item
prompt_item 1 --- n prompt_item_version
prompt_policy 1 --- n prompt_policy_version
prompt_item_version n --- n prompt_policy_version（逻辑引用，通过 key 集合）
prompt_item_version 1 --- n prompt_runtime_snapshot_ref
```

注意：

- `prompt_policy_version` 与 `prompt_item_version` 不一定必须用中间关系表
- 如果策略包只是保存 key 列表，那么逻辑引用即可
- 若后续策略复杂度提升，再拆 `prompt_policy_item_relation`

---

## 7. 核心字段规范建议

## 7.1 分类字段规范

### `category_key`
建议统一小写短横线风格：

- `memory-hint`
- `rag-hint`
- `agent-local`

避免中英文混排。

---

## 7.2 Prompt key 规范

建议统一：

```text
{category}.{subCategory}.{name}_{versionTag}
```

例如：

- `persona.maid.gentle_v1`
- `scene.tavern.night_v1`
- `repair.main.json_v1`
- `agent-local.reconstruction.default_v1`

兼容别名规范（仅用于历史兼容，不作为主规范）：

- 允许短 key / 下划线 key：如 `maid_gentle_v1`、`persona.maid_gentle_v1`
- 允许 `agent-local.*` 与 `agent.*` 互为别名：如 `agent-local.reconstruction.default_v1` <-> `agent.reconstruction.default_v1`

文档示例默认展示主 key，并在示例处标注对应别名 key。

---

## 7.3 runtime_slot 规范

建议固定词表，避免自由字符串泛滥。  
例如：

- `instructions.system`
- `instructions.persona`
- `instructions.scene`
- `memory.hints`
- `knowledge.evidence`
- `output.constraints`
- `runtime.prompt`
- `agent.reconstruction`
- `agent.summary`
- `repair.main`

这对后续和 `DefaultContextAssembler` 做映射非常重要 [2]。

---

## 7.4 assembly_mode 规范

建议固定枚举：

- `ALWAYS`
- `KEYWORD_ONLY`
- `AGENT_ONLY`
- `KEYWORD_AND_AGENT`
- `KEYWORD_OR_AGENT`
- `POLICY_ONLY`
- `MANUAL_ONLY`
- `DISABLED`

---

## 7.5 status 规范

建议统一：

- 条目表：`enabled / disabled`
- 版本表：`draft / active / archived`

不要混太多状态，个人应用保持简单即可。

---

## 8. 典型数据流与表使用方式

---

## 8.1 前端查询分类列表

来源：

- `prompt_category`

返回：

- 分类 key
- 名称
- 排序
- 是否允许关键词匹配

---

## 8.2 前端查询某分类 key/value

流程：

1. 从 `prompt_item` 过滤 `category_key`
2. 找到其 `current_version_id`
3. 去 `prompt_item_version` 取 `prompt_value`
4. 组装为：

```json
{
  "category": "persona",
  "items": {
    "persona.maid.gentle_v1": "你是一名温柔、克制、善于照顾用户情绪的女仆角色。",
    "persona.maid.strict_v1": "你是一名严谨、注重礼仪的女仆角色。"
  }
}
```

---

## 8.3 前端新增内容型条目

涉及表：

- `prompt_item`
- `prompt_item_version`

规则：

- `has_template_variables = false`
- 分类必须属于内容型
- 默认 `assembly_mode` 不可设为危险执行型模式

---

## 8.4 前端删除内容型条目

操作：

- `prompt_item.enabled = false`
- 或软删除标记

建议不要物理删除，便于回滚与历史引用保留。

---

## 8.5 前端修改执行型条目

操作：

1. 新建 `prompt_item_version`
2. 填写新的 `prompt_value`
3. 更新 `prompt_item.current_version_id`
4. 写入 `change_note`

执行型条目不允许删除，但允许新版本覆盖。

---

## 8.6 运行时 Resolver 取数

涉及表：

- `prompt_item`
- `prompt_item_version`
- `prompt_policy_version`

取数逻辑：

1. 取所有 `enabled = true` 且当前版本为 active 的条目
2. 按：
    - category
    - assembly_mode
    - keyword_match_enabled
    - match_scope
    - runtime_slot
      做索引
3. 根据当前上下文做匹配

这与当前状态驱动、section 化装配的主链路兼容 [2]。

---

## 8.7 快照记录 Prompt 引用

主模型执行完成后，将命中的条目写入：

- `prompt_runtime_snapshot_ref`

用于支持回放：

- 本轮用了哪些 Prompt
- 为什么命中
- 用了哪个版本

这正是当前架构下一阶段最缺失的能力之一 [2]。

---

## 9. 数据层如何映射当前项目实际 Prompt 资产

根据当前项目分析，现有 Prompt 来源主要有两类 [2]：

### 9.1 基础模板层
来源于 `PromptTemplates`：

- `SYSTEM_PROMPT`
- `RUNTIME_PROMPT`
- `REPAIR_PROMPT`
- `MASTER_PLANNING_PROMPT`
- `TOOL_ARGS_PROMPT`

映射建议：

- 导入为 `prompt_item`
- 标记 `is_builtin = true`
- 分类分别归入：
    - `system`
    - `repair`
    - `tool`
    - `format`
    - `task`

---

### 9.2 Agent 局部模板层
来源于各 `Default*Agent` [2]：

- `RECONSTRUCTION_PROMPT`
- `GLOBAL_RERANK_PROMPT`
- `RECOVERY_DECISION_PROMPT`
- `TOOL_SEMANTIC_PROMPT`
- `SUMMARY_PROMPT`

映射建议：

- 分类归为 `agent-local` / `summary` / `tool`
- `has_template_variables = true`
- `assembly_mode = AGENT_ONLY`
- `runtime_slot` 指向 Agent 位点

---

## 10. 示例记录设计

---

## 10.1 内容型条目示例

### `prompt_item`

- `category_key`: `persona`
- `sub_category`: `maid`
- `prompt_key`: `persona.maid.gentle_v1`（别名：`maid_gentle_v1` / `persona.maid_gentle_v1`）
- `runtime_slot`: `instructions.persona`
- `has_template_variables`: `false`
- `keyword_match_enabled`: `true`
- `assembly_mode`: `KEYWORD_ONLY`
- `enabled`: `true`
- `priority`: `80`

### `prompt_item_version`

- `version_no`: `1.0.0`
- `prompt_value`: `你是一名温柔、克制、善于照顾用户情绪的女仆角色。`
- `template_variables`: `[]`
- `match_keywords`: `["温柔","女仆","陪伴","治愈"]`
- `match_scope`:

```json
{
  "agents": ["MAIN_CHAT_AGENT"],
  "nodeKinds": ["CHAT_TURN"],
  "taskStates": ["executing", "waiting"]
}
```

- `edit_policy`:

```json
{
  "create": true,
  "update": true,
  "delete": true
}
```

---

## 10.2 执行型条目示例

### `prompt_item`

- `category_key`: `agent-local`
- `sub_category`: `reconstruction`
- `prompt_key`: `agent-local.reconstruction.default_v1`（别名：`agent.reconstruction.default_v1` / `reconstruction_default_v1`）
- `runtime_slot`: `agent.reconstruction`
- `has_template_variables`: `true`
- `keyword_match_enabled`: `false`
- `assembly_mode`: `AGENT_ONLY`
- `enabled`: `true`
- `priority`: `100`

### `prompt_item_version`

- `version_no`: `1.0.0`
- `prompt_value`: `请根据原始输入、上下文和缺失槽位，输出规范化意图：${normalizedIntent}。`
- `template_variables`: `["normalizedIntent","missingSlots"]`
- `match_keywords`: `[]`
- `match_scope`:

```json
{
  "agents": ["INPUT_RECONSTRUCTION_AGENT"],
  "nodeKinds": ["CHAT_PRE_TOOL"],
  "taskStates": ["planning"]
}
```

- `edit_policy`:

```json
{
  "create": false,
  "update": true,
  "delete": false
}
```

---

## 11. 索引与查询设计建议

本节不写 SQL，只描述架构层建议。

---

### 11.1 分类查询相关索引

建议支持：

- 按 `category_key` 查询
- 按 `enabled` 查询
- 按 `current_version_id` 关联查询

适用于前端分类浏览。

---

### 11.2 key 查询相关索引

建议保证：

- `prompt_key` 全局唯一
- `policy_key` 全局唯一

因为运行时和前端都高度依赖 key。

---

### 11.3 当前 active 版本查询优化

建议优化：

- `prompt_item.current_version_id`
- `prompt_item_version.is_active`

运行时只消费 active 版本，因此该路径必须高效。

---

### 11.4 关键词匹配相关索引

如果你后续 Prompt 数量变多，建议重点优化：

- `match_keywords`
- `category_key`
- `keyword_match_enabled`

因为酒馆式内容型 Prompt 的主要动态匹配路径就在这里。

---

### 11.5 runtime_slot / assembly_mode 查询优化

运行时 Resolver 经常会按：

- `assembly_mode`
- `runtime_slot`
- `category_key`

过滤 Prompt 条目，因此这些字段需要重点考虑索引。

---

## 12. 软删除与归档建议

个人应用也建议采用软删除，而不是物理删除。

---

## 12.1 原因

因为 Prompt 条目一旦被：

- 历史快照引用
- 历史版本引用
- 策略包引用

物理删除会导致审计链断裂。

---

## 12.2 建议方式

### 条目主表
使用：

- `enabled`
- 或 `deleted_at`

### 版本表
使用：

- `status = archived`

这样就能做到：

- 前端看不到
- 历史快照还能回放
- 版本还能保留

---

## 13. 表设计对当前架构演进的价值

该数据表方案不是单纯为“存 Prompt 文本”服务，而是为当前项目下一阶段演进提供基础 [2]：

1. 解决 Prompt 定义分散问题
2. 为工具决策模板统一真源提供载体
3. 为双消息化、分层式 Prompt 管理提供可落地元数据支持
4. 为输出契约统一、版本快照、问题回放提供基础 [2]
5. 让 Prompt 真正成为系统可测试、可回归、可观测的一部分 [1]

---

## 14. 推荐最小落地子集

如果你想先最小实现，不需要一次上满全部表。

建议第一阶段先落这 4 张：

1. `prompt_category`
2. `prompt_item`
3. `prompt_item_version`
4. `prompt_runtime_snapshot_ref`

这 4 张已经足够支持：

- 分类管理
- 条目管理
- 版本切换
- 前端热修改
- 快照回放

等后续你开始需要“固定策略包”时，再加：

5. `prompt_policy`
6. `prompt_policy_version`

---

## 15. 最终结论

本数据表设计的核心思想是：

- **用 `prompt_item` 管 Prompt 身份**
- **用 `prompt_item_version` 管 Prompt 内容与演进**
- **用 `prompt_policy` 管稳定组合**
- **用 `prompt_runtime_snapshot_ref` 管运行时引用与回放**

这样做的价值在于：

1. 保持与当前项目“状态驱动的多 Prompt 协同编排架构”兼容 [2]
2. 不修改 `context / map / memory / rag` 原有边界
3. 将 Prompt 从代码常量升级为结构化系统资产
4. 支持酒馆式分类管理
5. 支持内容型 Prompt 灵活编辑
6. 支持执行型 Prompt 结构保护
7. 支持版本治理、问题回放和后续系统化演进 [1][2]

---

## 16. 文档摘要

一句话总结：

> PG 版 Prompt 数据表设计，不是把 Prompt 简单搬进数据库，而是为当前已有的编排式 Prompt 主链路补上一层结构化真源和版本治理能力，让 Prompt 具备分类、匹配、装配、回放和演进的系统属性 [1][2]。


---

## 规范冲突统一说明（生效日期：2026-04-08）

以下为跨文档冲突点统一口径，仅用于验收与实施对齐：

1) prompt_item.status 口径统一
- 统一为：enabled / disabled（条目启停状态）。
- active 仅用于版本语义（例如 prompt_item_version 当前生效版本），不再作为 prompt_item.status 示例值。

2) PromptSnapshotBridge 时机口径统一
- 主模型执行前完成 snapshot bridge payload 构建并进入上下文快照。
- 主模型执行完成后写入 prompt_runtime_snapshot_ref（运行时引用落表）。

3) prompt_key 口径统一（主规范 + 兼容别名）
- 主规范：`{category}.{subCategory}.{name}_{versionTag}`，示例：`persona.maid.gentle_v1`、`agent-local.reconstruction.default_v1`。
- 兼容别名：运行时继续兼容历史 key（例如 `maid_gentle_v1`、`persona.maid_gentle_v1`、`agent.reconstruction.default_v1`），但文档示例默认展示主规范 key，并在示例处标注对应别名。

说明：若历史段落与本节冲突，以本节为准。
