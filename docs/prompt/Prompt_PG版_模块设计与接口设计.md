# Prompt_PG版_模块设计与接口设计.md

## 1. 文档目标

本文档用于在既定的 **PG 版 Prompt 工程架构** 基础上，进一步细化系统的**模块划分**与**接口设计**。  
目标是在**不修改现有 `context / map / memory / rag` 职责边界**的前提下，为当前项目新增一套适合个人应用的、酒馆式 Prompt 分类治理能力，并与当前“**编排驱动 + 分层注入 + 结构化约束**”的主链路兼容 [2]。

本文档不展开：

- SQL 表设计
- 缓存加载实现细节
- 具体代码实现

本文档聚焦：

- 模块职责划分
- 模块交互关系
- 运行时调用链
- 前后端接口设计
- 关键对象定义
- 与现有 Prompt 主链路的集成点

---

## 2. 设计原则

### 2.1 不推翻现有主链路

当前项目已经具备较成熟的 Prompt 主链路，包括：

- 状态机驱动
- 输入重构、重排、恢复决策
- `DefaultContextAssembler` section 化组装
- 主模型 JSON 强约束 + repair 回路
- `FINAL_MODEL_CONTEXT` 快照审计 [2]

因此新设计遵循：

- 不重写现有 `StateDrivenContextPipelineImpl`
- 不重写 `RoundPipelineOrchestratorImpl`
- 不重写 `TaskOrchestratorServiceImpl`
- 不重写 `DefaultContextAssembler`
- 不改动 `context / map / memory / rag`

新模块只作为 **Prompt 统一治理层** 接入。

---

### 2.2 Prompt 是配置与代码之间的中间层

Prompt 不应继续只是代码常量，而应像配置一样被统一管理、版本化、可回放、可比对 [1]。  
同时，Prompt 应表达策略，而程序应表达规则 [1]。因此：

- Prompt 条目负责描述角色、风格、策略、格式提示
- 程序负责删除限制、匹配规则、装配顺序、校验与回退

---

### 2.3 个人应用优先轻量化

本项目是个人使用应用，不引入企业级权限、审批、多租户治理。  
系统重点在于：

- 分类清晰
- 编辑方便
- 热修改
- 稳定装配
- 版本回滚
- 易调试、易预览

---

## 3. 模块总览

## 3.1 新增模块清单

建议新增以下模块：

1. `PromptRegistryModule`
2. `PromptQueryModule`
3. `PromptMutationModule`
4. `PromptVersionModule`
5. `PromptResolverModule`
6. `PromptPolicyModule`
7. `PromptPreviewModule`
8. `PromptSnapshotBridgeModule`
9. `PromptAdminApiModule`
10. `PromptFrontendAdapterModule`

---

## 3.2 模块关系图

```text
┌─────────────────────────────────────┐
│         PromptFrontendAdapter       │
│   前端分类展示 / 编辑表单 / 预览结果   │
└─────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────┐
│         PromptAdminApiModule        │
│    查询接口 / 保存接口 / 预览接口     │
└─────────────────────────────────────┘
                    │
     ┌──────────────┼──────────────┐
     ▼              ▼              ▼
┌───────────┐ ┌────────────┐ ┌──────────────┐
│PromptQuery│ │PromptMutation│ │PromptVersion │
└───────────┘ └────────────┘ └──────────────┘
       │              │               │
       └──────┬───────┴───────┬──────┘
              ▼               ▼
        ┌──────────────────────────┐
        │    PromptRegistryModule   │
        │   Prompt真源统一访问层     │
        └──────────────────────────┘
                      │
                      ▼
        ┌──────────────────────────┐
        │    PromptResolverModule   │
        │ 匹配 / 去重 / 排序 / 装配选择 │
        └──────────────────────────┘
                      │
          ┌───────────┼───────────┐
          ▼                       ▼
┌──────────────────┐   ┌──────────────────────┐
│DefaultContext    │   │ Default*Agent Prompt │
│Assembler 集成点  │   │ 局部Prompt集成点      │
└──────────────────┘   └──────────────────────┘
                      │
                      ▼
        ┌──────────────────────────┐
        │ PromptSnapshotBridgeModule│
        │ 快照记录 Prompt引用版本    │
        └──────────────────────────┘
```

---

## 4. 核心模块设计

## 4.1 PromptRegistryModule

### 4.1.1 模块职责

`PromptRegistryModule` 是整个新架构的核心统一访问层，负责：

- 读取当前生效 Prompt 条目
- 按分类组织 Prompt
- 按 key 查询 Prompt
- 返回条目完整元数据
- 为 Resolver 提供标准化 Prompt 视图
- 对接 PG 中的 Prompt 真源

它是“Prompt 真源访问门面”，但不负责复杂业务决策。

---

### 4.1.2 输入输出

#### 输入
- 分类
- key
- 条件过滤器
- 版本状态
- 条目 ID

#### 输出
- Prompt 条目对象
- 条目列表
- 分类树
- 当前 active 版本引用

---

### 4.1.3 建议暴露的服务接口

```java
public interface PromptRegistryService {
    PromptItemDTO getByKey(String key);
    PromptItemDTO getById(String id);
    List<PromptItemDTO> listByCategory(String category);
    List<PromptItemDTO> listAllActive();
    Map<String, String> listKeyValueByCategory(String category);
    boolean existsByKey(String key);
}
```

---

## 4.2 PromptQueryModule

### 4.2.1 模块职责

提供面向前端和预览器的查询能力：

- 分类列表查询
- 分类下 key/value 查询
- 条目详情查询
- 关键词过滤查询
- 按装配模式查询
- 按是否含模板变量查询

该模块是只读查询服务，不处理修改。

---

### 4.2.2 设计目标

酒馆式 Prompt 管理的核心体验是“像词条库一样浏览和搜索 Prompt”，因此 Query 模块需要重点优化：

- 分类浏览
- 简化返回结构
- 条目搜索体验

---

### 4.2.3 建议服务接口

```java
public interface PromptQueryService {
    List<String> listCategories();
    CategoryPromptView getCategoryView(String category);
    PromptItemDetailView getDetailByKey(String key);
    List<PromptItemSummaryView> search(PromptSearchRequest request);
}
```

---

## 4.3 PromptMutationModule

### 4.3.1 模块职责

负责 Prompt 条目的新增、修改、删除行为。

但注意：

- 这里只负责“写操作流程”
- 不负责最终是否允许删除的规则定义
- 删除限制等由条目结构规则判定

---

### 4.3.2 支持的操作

#### 内容型条目
允许：
- 新增
- 修改
- 删除

#### 执行型条目
允许：
- 修改
- 不允许删除
- 不允许新增同类执行型条目

该限制是结构限制，不是权限限制，符合“用 Prompt 表达策略，用程序表达规则”的原则 [1]。

---

### 4.3.3 建议服务接口

```java
public interface PromptMutationService {
    SavePromptResult createPrompt(CreatePromptRequest request);
    SavePromptResult updatePrompt(UpdatePromptRequest request);
    DeletePromptResult deletePrompt(String key);
}
```

---

## 4.4 PromptVersionModule

### 4.4.1 模块职责

负责 Prompt 版本管理：

- 保存新版本
- 激活版本
- 回滚版本
- 查询历史版本
- 比较版本差异

由于 Prompt 工程应作为可演进、可回归的系统部件管理，因此版本化是必要能力 [1]。

---

### 4.4.2 建议服务接口

```java
public interface PromptVersionService {
    List<PromptVersionView> listVersions(String key);
    PromptVersionView getVersionDetail(String versionId);
    ActivateVersionResult activateVersion(String versionId);
    RollbackResult rollbackToVersion(String key, String versionId);
    PromptDiffView diff(String versionIdA, String versionIdB);
}
```

---

## 4.5 PromptPolicyModule

### 4.5.1 模块职责

用于管理 Prompt 策略包，即一组稳定的 Prompt 基线组合。

适用场景：

- 酒馆默认聊天策略
- 固定角色预设
- 固定场景模板包
- 某类会话模式

策略包可以降低运行时波动，让一部分长期稳定规则显式固定下来。

---

### 4.5.2 建议服务接口

```java
public interface PromptPolicyService {
    PromptPolicyDTO getByPolicyId(String policyId);
    List<PromptPolicyDTO> listPolicies();
    SavePolicyResult savePolicy(SavePolicyRequest request);
    DeletePolicyResult deletePolicy(String policyId);
}
```

---

## 4.6 PromptResolverModule

### 4.6.1 模块职责

`PromptResolverModule` 是运行时最核心的决策模块，负责根据当前会话上下文选择应参与本轮组装的 Prompt 条目。

它负责：

- ALWAYS 条目选择
- 关键词匹配
- Agent/节点匹配
- Policy 显式装配
- 去重与优先级排序
- 输出 runtimeSlot 映射结果

---

### 4.6.2 为什么要独立成模块

当前项目已有 `DefaultContextAssembler`，但它主要负责**section 组装**，不是 Prompt 条目筛选器 [2]。  
新架构里需要一个显式 Resolver，来把“酒馆式管理视图”转换成“运行时装配视图”。

---

### 4.6.3 输入对象

建议输入：

```java
public class PromptResolveContext {
    private String sessionId;
    private String userInput;
    private String personaId;
    private String sceneId;
    private String policyId;
    private String agent;
    private String nodeKind;
    private String taskState;
    private String modelFamily;
}
```

---

### 4.6.4 输出对象

```java
public class PromptResolveResult {
    private List<ResolvedPromptItem> matchedItems;
    private Map<String, List<ResolvedPromptItem>> slotMapping;
}
```

---

### 4.6.5 核心职责拆分

建议内部拆为：

1. `AlwaysPromptSelector`
2. `KeywordPromptMatcher`
3. `AgentPromptMatcher`
4. `PolicyPromptSelector`
5. `PromptDeduplicator`
6. `PromptPrioritySorter`
7. `RuntimeSlotMapper`

---

### 4.6.6 推荐服务接口

```java
public interface PromptResolverService {
    PromptResolveResult resolve(PromptResolveContext context);
}
```

---

## 4.7 PromptPreviewModule

### 4.7.1 模块职责

用于给前端提供“匹配预览”和“装配预览”能力，避免用户改 Prompt 后直接影响运行时而不可控。

支持：

- 查看输入会命中哪些条目
- 查看最终 section 组装结果
- 查看关键词命中原因
- 查看哪些条目因结构规则未被装配

这很符合 Prompt 工程强调的可测试、可回归、可观测方向 [1]。

---

### 4.7.2 两类预览

#### A. 匹配预览
关注：
- 命中了哪些 Prompt
- 为什么命中

#### B. 装配预览
关注：
- 最终进入哪些 runtimeSlot
- 最终 section 长什么样

---

### 4.7.3 建议服务接口

```java
public interface PromptPreviewService {
    PromptMatchPreview previewMatch(PromptResolveContext context);
    PromptAssemblePreview previewAssemble(PromptResolveContext context);
}
```

---

## 4.8 PromptSnapshotBridgeModule

### 4.8.1 模块职责

该模块用于桥接现有快照体系，将本轮使用的 Prompt 条目和版本信息写入 `FINAL_MODEL_CONTEXT` 相关快照中 [2]。

当前系统已有 Prompt 快照，但还缺模板级版本治理信息 [2]。  
因此该模块负责补齐：

- prompt key
- prompt version
- policy id
- assembler version
- slot mapping

---

### 4.8.2 建议服务接口

```java
public interface PromptSnapshotBridgeService {
    PromptSnapshotPayload buildSnapshotPayload(PromptResolveResult result, String policyId);
}
```

---

## 4.9 PromptAdminApiModule

### 4.9.1 模块职责

向前端暴露统一 HTTP API，包括：

- 分类查询
- 条目查询
- 条目保存
- 条目删除
- 版本切换
- 预览接口
- 策略包查询

它本身不处理核心业务，只做：

- 参数接收
- DTO 转换
- 返回结构适配

---

## 4.10 PromptFrontendAdapterModule

### 4.10.1 模块职责

主要用于适配前端所需的数据结构，尤其是你要求的：

- 分类下 key/value 查询
- 条目详情页字段结构
- 预览页结构
- 版本列表结构

这个模块可以是独立 adapter，也可以直接放在 API 层中实现。

---

## 5. 与现有架构的集成点设计

## 5.1 与 `PromptTemplates` 的集成

当前 `PromptTemplates` 是基础模板常量层 [2]。  
新架构下建议其职责变为：

- 初始 Prompt 资产来源
- 本地默认模板 fallback
- 过渡期兼容层

不再作为唯一真源。

---

## 5.2 与 `Default*Agent` 的集成

当前存在多个 Agent 内置局部 Prompt [2]，例如：

- 输入重构
- 全局重排
- 恢复决策
- 工具语义
- 摘要

建议这些 Agent 在逻辑上保持不变，但 Prompt 内容改为从 Registry 获取。

集成方式：

- `DefaultInputReconstructionAgent` -> 按 `agent-local/reconstruction` 取 Prompt
- `DefaultSummaryAgent` -> 按 `summary` 分类取 Prompt
- `DefaultToolSemanticAgent` -> 按 `tool` 或 `agent-local` 取 Prompt

---

## 5.3 与 `DefaultContextAssembler` 的集成

### 5.3.1 集成定位

`DefaultContextAssembler` 继续负责：

- section 顺序
- 候选池裁剪
- runtime prompt 拼装
- snapshot 结构写入 [2]

新增集成点：

- 在组装前调用 `PromptResolverService.resolve`
- 将结果按 `runtimeSlot` 映射为 section 内容来源

---

### 5.3.2 推荐集成方式

可在 Assembler 入口新增：

```java
PromptResolveResult promptResolveResult = promptResolverService.resolve(context);
```

然后将 Resolver 结果与已有 context/memory/rag 输出共同组装。

这符合“提示词与上下文、工具、记忆共同工作”的思路 [1]。

---

## 5.4 与 `TaskOrchestratorServiceImpl` 的集成

`TaskOrchestratorServiceImpl` 继续负责：

- 主模型触发
- repair 回路
- fallback
- write round state [2]

新增集成：

- 主模型执行前，将本轮 PromptResolveResult 传入 snapshot bridge
- repair 流程也可通过 Resolver 查询 repair 类 Prompt

---

## 5.5 与 `LlmClientUtil` 的集成

`LlmClientUtil` 的安全门能力仍然保留 [2]：

- injection 检测
- user_input 包裹
- security notice

Prompt 新架构不替代这些安全逻辑。  
二者关系：

- Prompt Registry/Resolver 负责“用哪些 Prompt”
- `LlmClientUtil` 负责“如何安全送给模型”

---

## 6. 核心对象设计

## 6.1 PromptItem

```java
public class PromptItem {
    private String id;
    private String category;
    private String subCategory;
    private String key;
    private String name;
    private String value;
    private String runtimeSlot;
    private boolean hasTemplateVariables;
    private List<String> templateVariables;
    private boolean keywordMatchEnabled;
    private List<String> matchKeywords;
    private String assemblyMode;
    private MatchScope matchScope;
    private boolean enabled;
    private EditPolicy editPolicy;
    private Integer priority;
    private String status;
    private String version;
    private String changeNote;
}
```

---

## 6.2 MatchScope

```java
public class MatchScope {
    private List<String> agents;
    private List<String> nodeKinds;
    private List<String> taskStates;
    private List<String> modelFamilies;
}
```

---

## 6.3 EditPolicy

```java
public class EditPolicy {
    private boolean create;
    private boolean update;
    private boolean delete;
}
```

这里不是权限模型，而是结构性限制。

---

## 6.4 PromptPolicy

```java
public class PromptPolicy {
    private String policyId;
    private String name;
    private String description;
    private List<String> includeItems;
    private boolean enabled;
}
```

---

## 6.5 ResolvedPromptItem

```java
public class ResolvedPromptItem {
    private String key;
    private String version;
    private String category;
    private String runtimeSlot;
    private String assemblyMode;
    private String matchReason;
    private Integer priority;
    private String value;
}
```

---

## 7. 装配模式模块化设计

## 7.1 模式枚举

建议统一定义：

```java
public enum PromptAssemblyMode {
    ALWAYS,
    KEYWORD_ONLY,
    AGENT_ONLY,
    KEYWORD_AND_AGENT,
    KEYWORD_OR_AGENT,
    POLICY_ONLY,
    MANUAL_ONLY,
    DISABLED
}
```

---

## 7.2 匹配器接口

```java
public interface PromptMatcher {
    List<PromptItem> match(List<PromptItem> items, PromptResolveContext context);
}
```

不同模式可以有不同匹配器实现：

- `AlwaysPromptMatcher`
- `KeywordPromptMatcher`
- `AgentPromptMatcher`
- `PolicyPromptMatcher`

这样可以让后续规则扩展更清晰。

---

## 8. 前端接口设计

## 8.1 分类查询接口

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

## 8.2 分类下 key/value 查询

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

## 8.3 条目详情接口

```http
GET /api/prompt/item/detail?key=maid_gentle_v1
```

返回：

```json
{
  "key": "maid_gentle_v1",
  "name": "温柔女仆人设",
  "category": "persona",
  "value": "你是一名温柔、克制、善于照顾用户情绪的女仆角色。",
  "hasTemplateVariables": false,
  "keywordMatchEnabled": true,
  "matchKeywords": ["温柔", "女仆", "陪伴"],
  "assemblyMode": "KEYWORD_ONLY",
  "enabled": true,
  "version": "1.0.0"
}
```

---

## 8.4 新增条目接口

```http
POST /api/prompt/item/create
```

说明：

- 仅允许创建内容型条目
- 若条目含模板变量，服务层拒绝

---

## 8.5 修改条目接口

```http
POST /api/prompt/item/update
```

说明：

- 内容型、执行型都可修改
- 执行型修改建议配合预览

---

## 8.5.1 兼容路由说明（2026-04-08）

- 主接口口径：`POST /api/prompt/item/create` 与 `POST /api/prompt/item/update`
- 兼容保留：`POST /api/prompt/item/save` 作为历史路由，语义上等价于 create/update 的兼容入口
- 验收口径以 create/update 为准，`save` 仅用于存量客户端兼容

## 8.6 删除条目接口

```http
POST /api/prompt/item/delete
```

说明：

- 仅允许 `editPolicy.delete = true` 的条目
- 执行型条目返回“不支持删除”

---

## 8.7 搜索接口

```http
POST /api/prompt/search
```

支持按以下条件过滤：

- category
- subCategory
- hasTemplateVariables
- assemblyMode
- keywordMatchEnabled
- key/name/value 模糊匹配

---

## 8.8 版本列表接口

```http
GET /api/prompt/item/versions?key=maid_gentle_v1
```

---

## 8.9 激活版本接口

```http
POST /api/prompt/item/activate
```

---

## 8.10 回滚接口

```http
POST /api/prompt/item/rollback
```

---

## 8.11 匹配预览接口

```http
POST /api/prompt/preview/match
```

输入：

```json
{
  "userInput": "我想和一个温柔的女仆聊天",
  "agent": "MAIN_CHAT_AGENT",
  "nodeKind": "CHAT_TURN",
  "taskState": "executing",
  "policyId": "chat_default_v1"
}
```

返回：

```json
{
  "matchedItems": [
    {"key": "system.base_v1", "reason": "ALWAYS"},
    {"key": "maid_gentle_v1", "reason": "KEYWORD_ONLY"},
    {"key": "soft_companion_v1", "reason": "KEYWORD_ONLY"}
  ]
}
```

---

## 8.12 装配预览接口

```http
POST /api/prompt/preview/assemble
```

返回：

- 命中条目
- runtimeSlot 映射
- 最终 section 内容预览

---

## 9. 运行时调用链设计

## 9.1 正常聊天链路中的新调用点

结合当前正常请求链路 [2]，新增 Prompt 模块后的主模型执行流程建议为：

```text
ChatServiceImpl.chat
  -> StateDrivenContextPipelineImpl
  -> RoundPipelineOrchestratorImpl
  -> TaskOrchestratorServiceImpl.orchestrateMainModel
  -> PromptResolverService.resolve
  -> DefaultContextAssembler.assembleAndSnapshot
  -> LlmClientUtil.generate
  -> JSON validate / REPAIR_PROMPT / fallback
```

---

## 9.2 工具决策链路中的新调用点

当前工具决策链路使用受治理决策 workset [2]。  
新架构下建议：

```text
orchestrateToolDecisionNode
  -> PromptResolverService.resolve(context with TOOL_DECISION_AGENT)
  -> assemble tool decision prompt
  -> buildDecisionPrompt / TOOL_ARGS_PROMPT
```

这样可逐步消除工具决策 Prompt 双轨问题 [2]。

---

## 10. 关键规则设计

## 10.1 内容型与执行型分治

### 内容型 Prompt
- 可创建
- 可删除
- 可关键词匹配

### 执行型 Prompt
- 不可创建
- 不可删除
- 不参与关键词匹配
- 仅 agent/node/taskState 显式装配

这是整个系统稳定性的关键边界。

---

## 10.2 Prompt 与代码职责边界

根据 Prompt 工程方法论，Prompt 应表达策略，程序应表达规则 [1]。  
因此：

### 适合放 Prompt 的
- 角色性格
- 场景氛围
- 回答风格
- 检索消费提示
- 工具使用策略
- 失败时语言风格

### 不适合放 Prompt 的
- 删除限制
- 必填字段校验
- schema 合法性
- 节点路由
- 调用频率控制
- 最终安全拦截

---

## 11. 推荐目录划分

虽然本方案采用 PG 真源，但代码目录建议按职责组织：

```text
prompt/
├─ api/
│  ├─ PromptAdminController.java
│  └─ PromptPreviewController.java
├─ service/
│  ├─ PromptRegistryService.java
│  ├─ PromptQueryService.java
│  ├─ PromptMutationService.java
│  ├─ PromptVersionService.java
│  ├─ PromptResolverService.java
│  ├─ PromptPolicyService.java
│  └─ PromptPreviewService.java
├─ model/
│  ├─ PromptItem.java
│  ├─ PromptPolicy.java
│  ├─ PromptResolveContext.java
│  ├─ PromptResolveResult.java
│  ├─ ResolvedPromptItem.java
│  ├─ MatchScope.java
│  └─ EditPolicy.java
├─ matcher/
│  ├─ PromptMatcher.java
│  ├─ AlwaysPromptMatcher.java
│  ├─ KeywordPromptMatcher.java
│  ├─ AgentPromptMatcher.java
│  └─ PolicyPromptMatcher.java
├─ adapter/
│  ├─ PromptFrontendAdapter.java
│  └─ PromptSnapshotBridgeService.java
└─ enums/
   └─ PromptAssemblyMode.java
```

---

## 12. 落地顺序建议

### 第一步
先实现：
- `PromptRegistryService`
- `PromptQueryService`
- `PromptMutationService`

先打通前端管理闭环。

### 第二步
实现：
- `PromptResolverService`
- `PromptPreviewService`

打通匹配与预览能力。

### 第三步
将 Resolver 接入：
- `DefaultContextAssembler`
- 各 `Default*Agent`

### 第四步
接入：
- `PromptVersionService`
- `PromptSnapshotBridgeService`

补齐回滚与审计。

这个顺序与当前架构“先统一治理，再逐步替换来源”的现实相匹配 [2]。

---

## 13. 最终结论

本模块设计方案的核心思想是：

1. 在当前“状态驱动的多 Prompt 协同编排架构”之上新增 Prompt 统一治理层，而不是重写主链路 [2]
2. 通过 `PromptRegistryModule` 统一 Prompt 真源访问
3. 通过 `PromptResolverModule` 实现酒馆式分类 Prompt 到运行时装配视图的映射
4. 通过 `PromptMutationModule`、`PromptVersionModule`、`PromptPreviewModule` 支持前端可查、可改、可预览、可回滚
5. 继续坚持分层 Prompt、工作流控制、结构化输出的工程方向，以系统稳定性为目标，而不是继续堆单体 Prompt [1]

一句话总结：

> PG 版 Prompt 模块设计的重点，不是把 Prompt 简单存进数据库，而是围绕现有编排架构建立一套统一的 Prompt 访问、匹配、装配、版本和预览体系，让 Prompt 真正成为系统级资产，而不是分散在代码中的文本片段 [1][2]。
