# Prompt_Engineering_V2_PG版酒馆式分类治理架构设计.md

## 1. 文档目标

本文档用于在**不修改现有 `context / map / memory / rag` 架构职责**的前提下，为当前项目设计一套新的 Prompt 工程架构。  
该架构采用：

- **PostgreSQL 作为 Prompt 真源**
- **酒馆式分类管理**
- **前端按分类查询 Prompt 条目**
- **条目支持热修改**
- **内容型 Prompt 可新增/删除**
- **执行型 Prompt 受结构约束**
- **与当前“编排驱动 + 分层注入 + 结构化约束”的现有 Prompt 主链路兼容** [2]

本方案**不包含 SQL 表结构设计**，也**不包含缓存加载实现细节**，仅聚焦架构设计本身。

---

## 2. 项目定位

### 2.1 应用定位

本项目是一个**个人使用应用**，不是企业级 Prompt 平台。  
因此，本设计不追求：

- 多角色权限
- 审批流
- 多租户隔离
- 多人协作治理
- 复杂灰度系统

而重点追求：

- 结构简单
- 分类清晰
- 可前端管理
- 可版本回滚
- 可预览
- 可稳定接入当前主链路

---

### 2.2 Prompt 工程定位

在 Agent 场景中，提示词不只是文本输入，而是模型行为接口的一部分；它需要与上下文、工具、记忆、工作流共同工作，目标是提升系统稳定性，而不是单次效果 [1]。  
因此，新架构的目标不是继续堆“大 Prompt”，而是把 Prompt 变成：

- 可分类的资产
- 可治理的配置
- 可装配的运行时输入
- 可回溯的系统部件 [1]

---

## 3. 当前项目 Prompt 架构现状

根据当前项目的 Prompt 架构分析，现有系统已经不是“单体 Prompt”模式，而是一个**状态驱动的多 Prompt 协同编排架构** [2]。

### 3.1 当前架构核心特点

当前项目已有以下特征 [2]：

- 以状态机驱动 Prompt，而不是简单按接口调用驱动
- 在主模型前存在输入重构、检索召回、能力筛选、工具语义化等步骤
- Prompt 不是单段文本，而是由 `DefaultContextAssembler` 按 section 组装
- 主模型输出被强约束为 JSON，并带有 repair 回路与 fallback
- 系统有 `FINAL_MODEL_CONTEXT` 快照审计能力 [2]

---

### 3.2 当前 Prompt 资产层次

项目内 Prompt 资产主要分为四层 [2]：

1. **基础模板层**
    - `PromptTemplates`
    - 包含 `SYSTEM_PROMPT`、`RUNTIME_PROMPT`、`REPAIR_PROMPT` 等

2. **能力 Agent 模板层**
    - 各 `Default*Agent` 内部局部模板
    - 如输入重构、重排、摘要、恢复决策、工具语义等

3. **编排拼装层**
    - `DefaultContextAssembler`
    - `ContextNodeTemplatePolicy`

4. **调用与安全层**
    - `LlmClientUtil`
    - 包括 prompt injection 检测、用户输入包裹、安全 notice 注入 [2]

---

### 3.3 当前架构的优势

现有架构已经具备较成熟的 Prompt 工程能力 [2]：

- 有明确的主 Prompt 组装器
- 有状态驱动场景策略
- 有 JSON-only + repair 回路
- 有 Prompt 快照审计
- 有工具决策前的治理与签名保护

---

### 3.4 当前架构的短板

现有分析中也明确指出了几个痛点 [2]：

1. Prompt 定义仍然分散
2. 工具决策模板存在双轨
3. 输出解析/修复实现重复
4. 缺少 Prompt 模板级版本治理
5. 快照缺少模板 ID / version 等治理信息

因此，下一阶段的重点不是“再加模板”，而是**统一治理与版本化** [2]。

---

## 4. 新架构设计原则

### 4.1 不推翻现有主链路

本次设计不修改以下核心链路与职责：

- `StateDrivenContextPipelineImpl`
- `RoundPipelineOrchestratorImpl`
- `TaskOrchestratorServiceImpl`
- `DefaultContextAssembler`
- `LlmClientUtil`
- `context / map / memory / rag`

即：

- 现有系统负责编排、上下文组织、能力供给、模型调用
- 新架构负责 Prompt 统一治理、分类管理、运行时匹配

---

### 4.2 Prompt 不再只是代码常量

Prompt 不应继续只以代码常量或 Agent 内嵌模板存在，而应被视为“配置与代码之间的中间层”进行管理 [1]。  
因此，新架构中 Prompt 的真源将从“分散常量”升级为：

- PostgreSQL 中的 Prompt 条目
- 统一注册
- 统一分类
- 统一版本化
- 统一匹配与组装

---

### 4.3 采用“酒馆式分类管理 + 受控运行时装配”

前端管理视角采用酒馆式体验：

- 按分类查看 Prompt
- 每个分类下显示 Prompt 条目
- 每个条目以 key/value 形式管理
- 支持修改、预览、回滚

但运行时不应按分类直接全量拼接，而应继续遵循当前系统的：

- 状态驱动
- section 化组装
- token 预算裁剪
- repair 回路

因为控制上下文长度、动态装配上下文，比盲目堆长 Prompt 更重要 [1]。

---

### 4.4 用 Prompt 表达策略，用程序表达规则

本架构中：

- Prompt 负责表达策略、语气、行为指导、消费方式
- 程序负责表达确定性规则、字段约束、节点路由、版本选择、删除限制 [1]

例如：

- “当信息不足时应保守回答”可以写在 Prompt 中
- “执行型 Prompt 不允许删除”应由系统规则控制

---

## 5. 总体架构设计

## 5.1 架构定位

新增两层核心能力：

1. **Prompt Registry**
    - Prompt 条目统一真源映射层
    - 提供分类查询与版本治理能力

2. **Prompt Resolver**
    - 根据当前运行时场景进行 Prompt 条目匹配
    - 输出最终应参与组装的 Prompt 集合

---

## 5.2 总体架构图

```text
┌────────────────────────────────────┐
│          Frontend Prompt UI         │
│ 分类查询 / 条目编辑 / 新增删除 / 预览 │
└────────────────────────────────────┘
                  │
                  ▼
┌────────────────────────────────────┐
│         Prompt Registry             │
│ Prompt真源映射 / 分类管理 / 版本治理  │
└────────────────────────────────────┘
                  │
                  ▼
┌────────────────────────────────────┐
│         Prompt Resolver             │
│ 关键词匹配 / Agent匹配 / Policy匹配  │
└────────────────────────────────────┘
                  │
        ┌─────────┼─────────┐
        ▼         ▼         ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│DefaultContext│ │Agent-local │ │Output      │
│Assembler     │ │Prompt装配  │ │Contract层  │
└────────────┘ └────────────┘ └────────────┘
                  │
                  ▼
┌────────────────────────────────────┐
│ StateDriven Pipeline / Orchestrator │
│      保持现有状态驱动主链路不变       │
└────────────────────────────────────┘
```

---

## 5.3 新旧架构关系

现有架构继续保留 [2]：

- 输入重构
- 证据重排
- 工具治理
- section 化上下文工作集
- JSON 输出修复
- 快照审计

新架构只新增 Prompt 的：

- 统一真源
- 分类管理
- 匹配逻辑
- 元数据治理
- 版本化引用

即：

- **现有主链路负责执行**
- **新架构负责 Prompt 资产治理**

---

## 6. Prompt 分类体系设计

## 6.1 分类设计原则

为兼容“酒馆式管理”与当前执行架构，Prompt 分类分为两类视角：

### A. 前端管理视角
用于酒馆式浏览与编辑

### B. 运行时装配视角
用于映射到现有 `DefaultContextAssembler` 的 section 与 Agent 位点

---

## 6.2 前端管理分类

建议采用以下一级分类：

### 1）`system`
系统级长期规则  
包括：

- 主人格
- 全局约束
- 基础边界
- 全局安全提示

---

### 2）`persona`
角色人设类 Prompt  
包括：

- 性格
- 说话方式
- 行为风格
- 情绪基线

---

### 3）`scene`
场景类 Prompt  
包括：

- 场景环境
- 地点描述
- 时间氛围
- 世界状态片段

---

### 4）`corpus`
语料风格类 Prompt  
包括：

- 表达习惯
- 常用修辞
- 对话偏好
- 氛围化片段

---

### 5）`style`
风格增强类 Prompt  
包括：

- 温柔风格
- 克制风格
- 陪伴感
- 叙述节奏

---

### 6）`worldview`
世界观片段类 Prompt  
包括：

- 世界设定
- 组织关系
- 固定背景
- 规则说明

---

### 7）`relation`
关系设定类 Prompt  
包括：

- 用户与角色的关系
- 当前亲密度
- 互动边界

---

### 8）`task`
任务类 Prompt  
用于描述当前任务目标、对话目标、阶段性要求

---

### 9）`memory-hint`
记忆使用提示  
不存记忆本身，只存“如何使用记忆”的提示

---

### 10）`rag-hint`
检索使用提示  
不存检索内容本身，只存“如何使用检索内容”的提示

---

### 11）`tool`
工具调用类 Prompt  
包括：

- 工具选择策略
- 参数生成说明
- 工具结果解释

---

### 12）`format`
输出格式类 Prompt  
包括：

- JSON 输出约束
- 响应字段说明
- 格式规范

---

### 13）`repair`
修复类 Prompt  
包括：

- JSON 修复
- 二次纠偏
- 格式补救

---

### 14）`summary`
摘要类 Prompt  
包括：

- 回合摘要
- 状态总结
- 历史压缩

---

### 15）`guardrail`
边界与失败处理类 Prompt  
包括：

- 信息不足时如何回答
- 风险输入如何降级
- 越界请求如何回避

---

### 16）`agent-local`
Agent 局部执行 Prompt  
包括：

- 输入重构
- 全局重排
- 恢复决策
- 工具语义化
- RAG planner 多阶段模板

这与当前项目的 Prompt 资产实际分布一致 [2]。

---

## 7. Prompt 资产分型

本方案的关键不是只有“分类”，还包括**Prompt 条目类型分层**。

---

### 7.1 内容型 Prompt

内容型 Prompt 主要用于酒馆体验增强：

- `persona`
- `scene`
- `corpus`
- `style`
- `worldview`
- `relation`

特点：

- 通常不含模板变量
- 可支持新增/删除
- 可参与关键词匹配
- 主要用于自然语言体验增强

---

### 7.2 执行型 Prompt

执行型 Prompt 主要用于任务执行与结构治理：

- `tool`
- `repair`
- `summary`
- `format`
- `rag-hint`
- `memory-hint`
- `guardrail`
- `agent-local`
- 某些 `task`

特点：

- 通常包含模板变量
- 与当前代码结构、节点位点、JSON 契约强相关
- 不支持随意新增/删除
- 不参与关键词匹配
- 主要由 Agent / 节点 / 状态决定是否装配 [2]

---

## 8. Prompt 条目模型设计

## 8.1 前端展示形式

前端按分类查询时，返回结果可采用你要求的酒馆式结构：

```json
{
  "category": "persona",
  "items": {
    "maid_gentle_v1": "你是一名温柔、克制、善于照顾用户情绪的女仆角色。",
    "maid_strict_v1": "你是一名严谨、注重礼仪与秩序的女仆角色。"
  }
}
```

此结构适合快速浏览与编辑。

---

## 8.2 后端完整条目模型

为了支持运行时匹配和结构治理，底层条目需要完整元数据。

建议模型如下：

```json
{
  "id": "prompt_2001",
  "category": "persona",
  "subCategory": "maid",
  "key": "maid_gentle_v1",
  "name": "温柔女仆人设",
  "value": "你是一名温柔、克制、善于照顾用户情绪的女仆角色。",
  "runtimeSlot": "instructions.persona",
  "hasTemplateVariables": false,
  "templateVariables": [],
  "keywordMatchEnabled": true,
  "matchKeywords": ["温柔", "女仆", "陪伴", "照顾", "治愈"],
  "assemblyMode": "KEYWORD_ONLY",
  "matchScope": {
    "agents": ["MAIN_CHAT_AGENT"],
    "nodeKinds": ["CHAT_TURN"],
    "taskStates": ["executing", "waiting"]
  },
  "enabled": true,
  "editPolicy": {
    "create": true,
    "update": true,
    "delete": true
  },
  "priority": 80,
  "status": "active",
  "version": "1.0.0",
  "changeNote": "新增温柔女仆人设"
}
```

---

## 8.3 字段说明

### 基础字段
- `id`：条目唯一标识
- `category`：一级分类
- `subCategory`：二级分类
- `key`：Prompt 条目 key
- `name`：展示名称
- `value`：Prompt 内容

### 结构字段
- `runtimeSlot`：运行时插入位点
- `hasTemplateVariables`：是否含模板变量
- `templateVariables`：模板变量列表

### 匹配字段
- `keywordMatchEnabled`：是否允许关键词匹配
- `matchKeywords`：关键词列表
- `assemblyMode`：装配模式
- `matchScope`：匹配范围

### 治理字段
- `enabled`：是否启用
- `editPolicy`：结构性可操作规则
- `priority`：优先级
- `status`：版本状态
- `version`：版本号
- `changeNote`：修改说明

---

## 9. 模板变量治理设计

## 9.1 为什么必须有 `hasTemplateVariables`

由于当前项目存在大量与代码链路强耦合的 Prompt，例如：

- 输入重构 Prompt
- 工具参数生成 Prompt
- JSON repair Prompt
- summary Prompt
- RAG planner Prompt [2]

这些 Prompt 中通常含有运行时变量，因此必须显式标识：

```json
"hasTemplateVariables": true
```

---

## 9.2 模板变量的意义

`hasTemplateVariables` 用于区分：

### A. 纯内容型条目
不依赖运行时变量  
例如：
- 人设
- 场景
- 风格
- 语料

### B. 执行型条目
与当前代码装配结构强绑定  
例如：
- `${normalizedIntent}`
- `${missingSlots}`
- `${toolEvidence}`
- `${invalidJson}`

这些条目不能像普通文案一样自由增删，否则容易破坏执行链路。

---

## 10. 条目可编辑规则

本项目是个人应用，不引入权限体系，因此条目的可操作性由**条目结构属性**决定，而不是由角色权限决定。

---

### 10.1 内容型条目规则

若：

- `hasTemplateVariables = false`
- 且分类属于内容型 Prompt

则建议：

```json
"editPolicy": {
  "create": true,
  "update": true,
  "delete": true
}
```

这类条目支持：

- 前端新增
- 前端删除
- 热修改
- 快速复制

---

### 10.2 执行型条目规则

若：

- `hasTemplateVariables = true`
- 或分类属于执行型 Prompt

则建议：

```json
"editPolicy": {
  "create": false,
  "update": true,
  "delete": false
}
```

即：

- 不允许新增同类执行型条目
- 不允许删除
- 允许谨慎修改
- 修改后建议先预览再生效

这是结构保护，不是权限控制，符合“用程序表达规则”的原则 [1]。

---

## 11. 关键词匹配设计

## 11.1 匹配目标

为满足酒馆式体验，内容型 Prompt 应支持通过关键词快速匹配。

例如用户输入：

```text
我想和一个温柔一点的女仆聊天
```

可以命中：

- `persona.maid_gentle_v1`
- `style.soft_companion_v1`

---

## 11.2 关键词字段

建议新增字段：

```json
"matchKeywords": ["温柔", "女仆", "陪伴", "治愈"]
```

并使用：

```json
"keywordMatchEnabled": true
```

控制该条目是否参与关键词匹配池。

---

## 11.3 关键词匹配范围限制

关键词匹配**仅适用于非执行类 Prompt**，包括：

- `persona`
- `scene`
- `corpus`
- `style`
- `worldview`
- `relation`

以下分类默认不参与关键词匹配：

- `tool`
- `repair`
- `summary`
- `format`
- `memory-hint`
- `rag-hint`
- `guardrail`
- `agent-local`

原因是执行型 Prompt 必须由当前节点、任务状态和编排逻辑显式控制，不能被用户输入隐式扰动 [2]。

---

## 12. Prompt 组装方式设计

每个 Prompt 条目必须声明其运行时装配方式，即：

```json
"assemblyMode": "KEYWORD_ONLY"
```

---

## 12.1 推荐的装配模式枚举

### 1）`ALWAYS`
始终组装  
适用：
- `system`
- 基础 `guardrail`
- 基础 `format`

---

### 2）`KEYWORD_ONLY`
仅关键词匹配  
适用：
- `persona`
- `scene`
- `corpus`
- `style`
- `worldview`
- `relation`

---

### 3）`AGENT_ONLY`
仅 Agent / 节点 / 状态匹配  
适用：
- `tool`
- `repair`
- `summary`
- `agent-local`
- 某些 `task`

---

### 4）`KEYWORD_AND_AGENT`
关键词与 Agent 同时命中才装配  
适用：
- 只在主聊天节点生效的风格增强 Prompt

---

### 5）`KEYWORD_OR_AGENT`
关键词或 Agent 任一命中即可  
适用：
- 某些弱增强条目
- 场景和角色联动条目

---

### 6）`POLICY_ONLY`
仅策略包显式装配  
适用：
- 固定组合模板
- 预设会话风格

---

### 7）`MANUAL_ONLY`
仅手动指定  
适用：
- 调试条目
- 特殊临时条目

---

### 8）`DISABLED`
禁用  
适用：
- 保留历史但不参与装配

---

## 12.2 匹配范围字段

建议配合：

```json
"matchScope": {
  "agents": ["MAIN_CHAT_AGENT"],
  "nodeKinds": ["CHAT_TURN"],
  "taskStates": ["planning", "executing"],
  "modelFamilies": ["gpt", "qwen"]
}
```

使条目匹配更加稳定、可控。

---

## 13. 运行时匹配与装配设计

## 13.1 两条匹配通道

为了兼容当前主链路，Prompt 匹配应拆成两条通道。

---

### A. 内容型匹配通道

匹配对象：

- 人设
- 场景
- 风格
- 语料
- 关系
- 世界观

匹配依据：

- 用户输入关键词
- 前端显式选择
- 会话标签
- 当前角色配置

---

### B. 执行型匹配通道

匹配对象：

- tool
- repair
- summary
- rag-hint
- format
- guardrail
- agent-local

匹配依据：

- 当前 agent
- 当前 nodeKind
- 当前 taskState
- 当前策略包
- 当前模型族

这样可保持当前状态驱动和受控编排逻辑不被破坏 [2]。

---

## 13.2 总装配顺序

建议最终装配顺序如下：

```text
1. ALWAYS 条目
2. AGENT_ONLY 执行型条目
3. KEYWORD_ONLY 内容型条目
4. POLICY_ONLY 条目
5. KEYWORD_AND_AGENT / KEYWORD_OR_AGENT 补充条目
6. 去重
7. 按 priority 排序
8. 映射到 runtimeSlot
9. 交给 DefaultContextAssembler 进入 section 组装
```

---

## 13.3 与当前 section 组装的映射

当前 `DefaultContextAssembler` 的核心 section 包括 [2]：

1. Instructions
2. Current Task State
3. Reconstructed User Intent
4. Relevant Knowledge Evidence
5. MCP Resource / Prompt Hints
6. Tool Evidence
7. Recent Interaction Context
8. Memory Hints
9. Output Constraints
10. Runtime Prompt

新架构不会改变这些 section，而是建立：

```text
Prompt条目 -> runtimeSlot -> assembler section
```

例如：

- `system.base_v1` -> `instructions.system` -> `Instructions`
- `persona.maid_gentle_v1` -> `instructions.persona` -> `Instructions`
- `memory-hint.default_v1` -> `memory.hints` -> `Memory Hints`
- `format.chat_json_v2` -> `output.constraints` -> `Output Constraints`

因此：

- 前端分类是管理视图
- runtimeSlot / section 才是运行时视图

---

## 14. Prompt Registry 设计

## 14.1 定位

Prompt Registry 是 Prompt 资产的统一真源映射层。  
其职责包括：

- 分类查询
- 条目查询
- 当前生效版本查询
- Prompt 条目注册
- Prompt 元数据统一管理
- 为 Resolver 提供标准化 Prompt 视图

---

## 14.2 与现有 Prompt 资产的衔接方式

第一阶段建议不重写全部 Prompt，而是先把现有模板统一纳入 Registry [2]：

- `PromptTemplates` 注册为基础模板条目
- 各 `Default*Agent` 的局部模板注册为 `agent-local` 条目
- 保留原代码常量作为过渡 fallback

这样做与现有架构分析中“先统一注册，不动主链路”的方向一致 [2]。

---

## 15. Prompt Resolver 设计

## 15.1 定位

Prompt Resolver 负责根据当前会话上下文、节点状态和用户输入，从 Registry 中选出本轮真正应参与组装的 Prompt 条目。

---

## 15.2 输入参数建议

Resolver 可接受如下输入：

```json
{
  "sessionId": "s_001",
  "personaId": "maid_gentle_v1",
  "sceneId": "tavern_night_v1",
  "policyId": "chat_default_v1",
  "userInput": "我想和你安静聊聊天",
  "agent": "MAIN_CHAT_AGENT",
  "nodeKind": "CHAT_TURN",
  "taskState": "executing",
  "modelFamily": "qwen"
}
```

---

## 15.3 输出结果建议

Resolver 应输出：

- 命中的 Prompt 条目
- 每条命中的原因
- section 映射结果
- 优先级排序结果

例如：

```json
{
  "matchedItems": [
    { "key": "system.base_v1", "reason": "ALWAYS" },
    { "key": "guardrail.safe_chat_v1", "reason": "ALWAYS" },
    { "key": "maid_gentle_v1", "reason": "KEYWORD_ONLY" },
    { "key": "tavern_night_v1", "reason": "POLICY_ONLY" },
    { "key": "format.chat_json_v2", "reason": "AGENT_ONLY" }
  ]
}
```

---

## 16. Prompt Policy 设计

为了避免每轮都从零做组合，建议增加 `Prompt Policy` 概念，用于定义一组稳定的基线 Prompt 组合。

例如：

```json
{
  "policyId": "chat_tavern_default_v1",
  "name": "酒馆默认闲聊策略",
  "include": [
    "system.base_v1",
    "guardrail.safe_chat_v1",
    "format.chat_json_v2",
    "memory-hint.default_v1",
    "rag-hint.default_v1"
  ]
}
```

其作用是：

- 固化长期稳定规则
- 让内容型 Prompt 只负责动态增强
- 避免每轮动态选择的波动过大

---

## 17. 与现有架构的集成方式

## 17.1 与 `PromptTemplates` 的关系

优化后，`PromptTemplates` 不再是唯一真源，而变成：

- 默认模板源
- 启动注册源
- fallback 源

真实使用的 Prompt 由 Registry 当前生效版本决定。

---

## 17.2 与 `Default*Agent` 的关系

各专项 Agent 仍保留原本职责 [2]，但其 Prompt 来源逐步统一到 Registry：

- 输入重构
- 全局重排
- 恢复决策
- 工具语义化
- 摘要

这样可以降低 Prompt 资产分散问题。

---

## 17.3 与 `DefaultContextAssembler` 的关系

Assembler 继续负责：

- section 组装
- 预算裁剪
- 最终 Prompt 生成
- 快照写入

只是在内容来源上，从“代码常量 + 局部拼接”演进为“Resolver 输出 + section 映射”。

---

## 17.4 与 `TaskOrchestratorServiceImpl` 的关系

`TaskOrchestratorServiceImpl` 继续负责 [2]：

- 主模型编排
- JSON 校验
- REPAIR_PROMPT 修复
- fallback 处理

Prompt 新架构不接管这些职责，只提供更干净的 Prompt 输入源。

---

## 17.5 与 `LlmClientUtil` 的关系

`LlmClientUtil` 继续负责：

- prompt injection 检测
- `<user_input>` 包裹
- system 安全提示注入 [2]

这些属于调用安全层，仍然保留。

---

## 18. 前端管理设计

## 18.1 设计目标

前端应具备“酒馆式 Prompt 管理”体验，而不是企业后台风格。

核心能力包括：

- 分类浏览
- 条目搜索
- key/value 展示
- 内容修改
- 内容型条目新增/删除
- 执行型条目预览
- 版本切换与回滚

---

## 18.2 列表展示建议

建议展示：

- 名称
- 分类
- key
- 是否含模板变量
- 是否参与关键词匹配
- 匹配模式
- 是否允许删除
- 当前状态
- 当前版本

---

## 18.3 编辑行为建议

### 对内容型条目
允许：

- 新增
- 编辑
- 删除
- 设置关键词
- 设置匹配模式

### 对执行型条目
允许：

- 编辑
- 查看模板变量
- 查看匹配范围
- 预览最终装配效果

不允许：

- 删除
- 新增同类执行型条目

---

## 19. 版本治理设计

## 19.1 简化版本状态

由于项目是个人应用，建议只保留三种版本状态：

- `draft`
- `active`
- `archived`

含义：

- `draft`：编辑中版本
- `active`：当前生效版本
- `archived`：历史归档版本

---

## 19.2 修改与生效原则

### 内容型 Prompt
建议允许直接修改后生效：

- persona
- scene
- style
- corpus
- worldview
- relation

### 执行型 Prompt
建议采用“先预览，再切换为 active”的方式：

- tool
- repair
- summary
- format
- agent-local

因为其与现有代码结构耦合更强，风险更高 [2]。

---

## 20. 快照与可观测性设计

## 20.1 为什么要补 Prompt 版本引用

当前系统已经支持 `FINAL_MODEL_CONTEXT` 快照 [2]，但现有分析也指出，缺少模板 ID / version，难以做模板级回放和回滚分析 [2]。

因此建议在快照中补充：

- `promptItemId`
- `promptKey`
- `promptVersion`
- `policyId`
- `assemblerVersion`

---

## 20.2 快照示例

```json
{
  "promptRefs": [
    { "key": "system.base_v1", "version": "1.0.0" },
    { "key": "maid_gentle_v1", "version": "1.1.0" },
    { "key": "format.chat_json_v2", "version": "2.0.0" }
  ],
  "policyId": "chat_default_v1",
  "assemblerVersion": "2.1.0"
}
```

这样可以支持：

- 回放
- 版本对比
- 条目回滚定位

---

## 21. API 能力设计

本节只描述接口职责，不展开 SQL 和缓存实现。

---

### 21.1 查询分类列表

```http
GET /api/prompt/categories
```

返回：

```json
[
  "system",
  "persona",
  "scene",
  "corpus",
  "style",
  "worldview",
  "relation",
  "task",
  "memory-hint",
  "rag-hint",
  "tool",
  "format",
  "repair",
  "summary",
  "guardrail",
  "agent-local"
]
```

---

### 21.2 查询分类下条目 key/value

```http
GET /api/prompt/items?category=persona
```

返回：

```json
{
  "category": "persona",
  "items": {
    "maid_gentle_v1": "你是一名温柔、克制、善于照顾用户情绪的女仆角色。",
    "maid_strict_v1": "你是一名严谨、注重礼仪与秩序的女仆角色。"
  }
}
```

---

### 21.3 查询条目详情

```http
GET /api/prompt/item/detail?key=maid_gentle_v1
```

---

### 21.4 保存条目

```http
POST /api/prompt/item/save
```

---

### 21.5 删除条目

```http
POST /api/prompt/item/delete
```

仅适用于 `editPolicy.delete = true` 的条目。

---

### 21.6 匹配预览

```http
POST /api/prompt/preview/match
```

用于查看某输入会命中哪些 Prompt 条目。

---

### 21.7 装配预览

```http
POST /api/prompt/preview/assemble
```

用于查看本轮最终会进入哪些 section。

---

### 21.8 版本切换/回滚

```http
POST /api/prompt/item/activate
POST /api/prompt/item/rollback
```

---

## 22. 示例条目

## 22.1 内容型条目：persona

```json
{
  "category": "persona",
  "subCategory": "maid",
  "key": "maid_gentle_v1",
  "name": "温柔女仆人设",
  "value": "你是一名温柔、克制、善于观察情绪变化的女仆角色。你说话礼貌、自然，不要机械重复设定。你需要在陪伴感、真实感和分寸感之间保持平衡。",
  "runtimeSlot": "instructions.persona",
  "hasTemplateVariables": false,
  "templateVariables": [],
  "keywordMatchEnabled": true,
  "matchKeywords": ["温柔", "女仆", "陪伴", "照顾", "治愈"],
  "assemblyMode": "KEYWORD_ONLY",
  "matchScope": {
    "agents": ["MAIN_CHAT_AGENT"],
    "nodeKinds": ["CHAT_TURN"],
    "taskStates": ["executing", "waiting"]
  },
  "enabled": true,
  "editPolicy": {
    "create": true,
    "update": true,
    "delete": true
  },
  "priority": 80,
  "status": "active",
  "version": "1.0.0"
}
```

---

## 22.2 内容型条目：scene

```json
{
  "category": "scene",
  "subCategory": "tavern",
  "key": "tavern_night_v1",
  "name": "夜晚酒馆大厅",
  "value": "当前场景为夜晚的酒馆大厅，空气中有淡淡酒香，木制桌椅在暖黄色灯光下显得安静而温暖。回复应自然融入环境，而不是机械叙述背景。",
  "runtimeSlot": "instructions.scene",
  "hasTemplateVariables": false,
  "templateVariables": [],
  "keywordMatchEnabled": true,
  "matchKeywords": ["酒馆", "夜晚", "安静", "陪伴"],
  "assemblyMode": "KEYWORD_OR_AGENT",
  "matchScope": {
    "agents": ["MAIN_CHAT_AGENT"],
    "nodeKinds": ["CHAT_TURN"]
  },
  "enabled": true,
  "editPolicy": {
    "create": true,
    "update": true,
    "delete": true
  },
  "priority": 70,
  "status": "active",
  "version": "1.0.0"
}
```

---

## 22.3 执行型条目：reconstruction

```json
{
  "category": "agent-local",
  "subCategory": "reconstruction",
  "key": "reconstruction_default_v1",
  "name": "输入重构模板",
  "value": "请根据原始输入、上下文和缺失槽位，输出规范化意图：${normalizedIntent}。同时识别缺失信息：${missingSlots}。",
  "runtimeSlot": "agent.reconstruction",
  "hasTemplateVariables": true,
  "templateVariables": ["normalizedIntent", "missingSlots"],
  "keywordMatchEnabled": false,
  "matchKeywords": [],
  "assemblyMode": "AGENT_ONLY",
  "matchScope": {
    "agents": ["INPUT_RECONSTRUCTION_AGENT"],
    "nodeKinds": ["CHAT_PRE_TOOL"],
    "taskStates": ["planning"]
  },
  "enabled": true,
  "editPolicy": {
    "create": false,
    "update": true,
    "delete": false
  },
  "priority": 100,
  "status": "active",
  "version": "1.0.0"
}
```

---

## 22.4 执行型条目：repair

```json
{
  "category": "repair",
  "subCategory": "json-repair",
  "key": "repair_main_json_v1",
  "name": "主模型 JSON 修复模板",
  "value": "你需要修复以下不符合 schema 的输出，并严格返回可解析 JSON：${invalidJson}",
  "runtimeSlot": "repair.main",
  "hasTemplateVariables": true,
  "templateVariables": ["invalidJson"],
  "keywordMatchEnabled": false,
  "matchKeywords": [],
  "assemblyMode": "AGENT_ONLY",
  "matchScope": {
    "agents": ["MAIN_MODEL_REPAIR_AGENT"],
    "nodeKinds": ["CHAT_TURN"]
  },
  "enabled": true,
  "editPolicy": {
    "create": false,
    "update": true,
    "delete": false
  },
  "priority": 100,
  "status": "active",
  "version": "1.0.0"
}
```

---

## 23. 落地步骤建议

## 第一步：建立 Prompt Registry 概念层
先在架构上引入 Registry，不改主链路。

目标：

- 让 Prompt 有统一真源概念
- 先完成资产可见性治理

---

## 第二步：统一登记现有 Prompt 资产
将现有所有 Prompt 登记为条目 [2]：

- `PromptTemplates`
- `DefaultInputReconstructionAgent`
- `DefaultGlobalContextRerankAgent`
- `DefaultRecoveryContextAgent`
- `DefaultToolSemanticAgent`
- `DefaultSummaryAgent`
- `ModelDrivenRagPlanner`

---

## 第三步：给条目补元数据
为每个 Prompt 增加：

- `category`
- `runtimeSlot`
- `hasTemplateVariables`
- `assemblyMode`
- `version`

---

## 第四步：实现 Prompt Resolver
先支持基础匹配：

- ALWAYS
- AGENT_ONLY
- KEYWORD_ONLY
- POLICY_ONLY

---

## 第五步：Assembler 接入 Resolver
保持当前 section 顺序不变，只把 Prompt 来源改成 Resolver 输出 [2]

---

## 第六步：前端接入酒馆式分类管理
优先实现：

- 分类列表
- 条目 key/value 查询
- 条目详情
- 内容型条目编辑与删除
- 执行型条目预览
- 版本切换与回滚

---

## 第七步：补齐快照里的 Prompt 版本引用
让 `FINAL_MODEL_CONTEXT` 能够回放 Prompt 版本组合 [2]

---

## 24. 方案收益

## 24.1 对当前项目的直接收益

该方案可以直接解决当前已知问题 [2]：

- 解决 Prompt 定义分散
- 解决模板来源不统一
- 为版本化回滚打基础
- 为后续统一输出契约和主链路双消息化提供前提 [2]

---

## 24.2 对酒馆体验的收益

该方案特别适合酒馆类应用，因为酒馆类应用高度依赖：

- 人设
- 场景
- 风格
- 世界观
- 关系
- 长期体验一致性

这些内容都非常适合做成内容型 Prompt 条目，并支持：

- 分类管理
- 关键词匹配
- 快速热修改

---

## 24.3 对系统稳定性的收益

方案并没有把控制逻辑重新堆回 Prompt，而是继续保留：

- 状态驱动编排
- section 化工作集
- JSON 强约束
- repair 回路
- 动态裁剪

这符合更稳的 Agent Prompt 工程方法：分层 Prompt、工作流控制、结构化输出 [1]。

---

## 25. 最终结论

本方案建议在现有项目之上新增一层：

**基于 PostgreSQL 真源的 Prompt Registry + Prompt Resolver 酒馆式分类治理架构**

其核心结论如下：

1. 当前项目已有较成熟的 Prompt 主链路，不应推翻，只应增强 [2]
2. Prompt 应被视为配置与代码之间的中间层，而不是继续散落在代码常量里 [1]
3. Prompt 条目应按分类管理，并支持前端按分类查询
4. 条目应同时具备内容字段和治理元数据
5. 通过 `hasTemplateVariables` 区分内容型与执行型 Prompt
6. 内容型 Prompt 支持新增、删除、热修改
7. 执行型 Prompt 受结构保护，不参与关键词匹配
8. 通过 `assemblyMode` 明确每条 Prompt 的装配方式
9. 通过 Resolver 将酒馆式分类管理映射到当前状态驱动、section 化组装主链路
10. 最终形成一个适合个人应用、易维护、可回滚、可扩展的 Prompt 工程系统

---

## 26. 文档摘要

一句话总结：

> 该方案不是重写当前 Prompt 系统，而是在现有“状态驱动的多 Prompt 协同编排架构”之上，新增一个基于 PostgreSQL 真源的 Prompt 分类治理层，让 Prompt 资产能够以酒馆式方式被管理、匹配、装配和回放，同时不破坏当前主链路的稳定性 [1][2]。
