# Prompt Engineering 架构分析报告（详细版）

## 1. 架构设计总览
当前项目的 Prompt Engineering 是“**编排驱动 + 分层注入 + 结构化约束**”的架构，不是简单拼一个 system prompt。

核心设计思想：
- 以状态机驱动 Prompt，而不是仅按接口调用驱动。
- 在主模型执行前，先完成输入重构、检索召回、能力筛选、工具语义化。
- Prompt 不是单块文本，而是 section 化上下文工作集（governed workset）。
- 输出强约束为 JSON，并提供 repair 回路与降级回路。

主链路入口：
- `ChatServiceImpl.chat`
- 经过 `StateDrivenContextPipelineImpl` + `RoundPipelineOrchestratorImpl`
- 最终落到 `TaskOrchestratorServiceImpl.orchestrateMainModel`
- 由 `DefaultContextAssembler` 组装最终 Prompt
- 由 `LlmClientUtil.generate` 发送到模型

---

## 2. Prompt 资产分层与治理边界

### 2.1 Prompt 资产分层
项目内 Prompt 资产分为四层：

1. 基础模板层（集中常量）
- 文件：`src/main/java/org/yilena/luna/prompt/PromptTemplates.java`
- 包含：`SYSTEM_PROMPT`、`RUNTIME_PROMPT`、`REPAIR_PROMPT`、`MASTER_PLANNING_PROMPT`、`TOOL_ARGS_PROMPT` 等。

2. 能力 Agent 模板层（局部模板）
- 代表类：
  - `DefaultInputReconstructionAgent` (`RECONSTRUCTION_PROMPT`)
  - `DefaultGlobalContextRerankAgent` (`GLOBAL_RERANK_PROMPT`)
  - `DefaultRecoveryContextAgent` (`RECOVERY_DECISION_PROMPT`)
  - `DefaultToolSemanticAgent` (`TOOL_SEMANTIC_PROMPT`)
  - `DefaultSummaryAgent` (`SUMMARY_PROMPT`)
  - `ModelDrivenRagPlanner`（query/source/stage/rerank 多模板）

3. 编排拼装层（运行时组装）
- `DefaultContextAssembler` 按 section 聚合素材并裁剪。
- `ContextNodeTemplatePolicy` 决定当前节点可见记忆范围和预算分配。

4. 调用与安全层（消息封装 + 过滤）
- `LlmClientUtil` 负责：
  - prompt injection 检测（`PROMPT_INJECTION_DETECTION`）
  - `<user_input>` 封装
  - 系统安全提示追加（`SYSTEM_SECURITY_NOTICE`）

### 2.2 治理边界
- LangChain4j `AiService` / `@Tool` 主链路已退场。
- 证据：`LunaAgentConfig` 注释明确说明 v2 不再依赖 AiServices/@Tool。
- 现状是“自建 Prompt 编排框架 + LangChain4j 作为模型调用适配层”。

---

## 3. 关键代码与职责映射（聚焦 Prompt）
| 位置 | 职责 | Prompt 作用 |
|---|---|---|
| `PromptTemplates` | 模板常量中心 | 提供主人格、运行时约束、修复、规划、工具参数模板 |
| `DefaultContextAssembler` | 最终 Prompt 拼装器 | 构建 sections，调用 `RUNTIME_PROMPT`，输出主模型工作集 |
| `ContextNodeTemplatePolicy` | 场景化策略 | 决定不同 task/node 下的记忆注入与 token 预算 |
| `TaskOrchestratorServiceImpl.orchestrateMainModel` | 主模型编排 | 触发 assembleAndSnapshot，调用主模型，repair，输出 reply |
| `TaskOrchestratorServiceImpl.invokeMainModel` | 主模型执行内核 | JSON 校验、`REPAIR_PROMPT` 修复、fallback |
| `AgentServiceImpl` | 工具决策与参数生成 | 工具决策 Prompt + TOOL/SKILL 参数 Prompt |
| `Default*Agent` 系列 | 专项子任务 | 输入重构、重排、恢复、工具语义、摘要的局部 Prompt |
| `DefaultThreeStageResponseService` | 三阶段响应生成 | task draft -> relational draft -> hybrid -> final JSON |
| `LlmClientUtil` | 统一调用安全门 | injection 检测、user 包裹、安全 notice 注入 |
| `ContextSnapshotStoreImpl` / `RuntimeAuditContextSnapshotWriter` | Prompt 快照审计 | 保存 FINAL_MODEL_CONTEXT（prompt、sections、token 统计） |

---

## 4. 一次正常请求的 Prompt 组装策略与流程（重点）

以下流程基于一次典型 `/chat` 正常请求（非阻断、非审批挂起）：

### 阶段A：预处理回合（不跑主模型）
1. 入口：`ChatServiceImpl.chat` 收到 `userInput`。
2. 调用 `stateDrivenContextPipeline.run`，trigger=`CHAT_PRE_TOOL`，`runMainModel=false`。
3. 在 `StateDrivenContextPipelineImpl.hydrateRoundRequest` 中补齐：
- `decision`
- `contextPackage`
- `reconstructionResult`
- `nodeWorksetResult`

这一阶段的 Prompt 相关工作：
- 输入重构 Prompt：`DefaultInputReconstructionAgent.RECONSTRUCTION_PROMPT`
- 全局重排 Prompt：`DefaultGlobalContextRerankAgent.GLOBAL_RERANK_PROMPT`
- 恢复决策 Prompt（如触发恢复场景）：`DefaultRecoveryContextAgent.RECOVERY_DECISION_PROMPT`

### 阶段B：工具决策前 Prompt（governed decision workset）
4. `TaskOrchestratorServiceImpl.orchestrateToolDecisionNode` 会先构建 `assembledDecisionContext`：
- 调用 `contextAssembler.assemble(..., toolDecisionPolicy, ...)`
- policy 为 `ContextNodeTemplatePolicy.forToolDecision(...)`
- 输出是“工具决策专用工作集 Prompt”

5. 将该工作集作为签名输入源：
- `ToolDecisionInputSignatureUtil.sign(sessionId, toolDecisionInput, assembledDecisionContext)`
- 目的是保证工具决策输入不可篡改。

6. `AgentServiceImpl.processToolCallingWithGovernance` 用该工作集构造工具决策 Prompt：
- `buildDecisionPrompt(assembledDecisionContext)`
- 让模型仅在受治理上下文下做 `tool_call / resource_read / prompt_get / workflow_start / direct_answer` 决策。

7. 若决定调用能力，再生成参数 Prompt：
- Tool：`PromptTemplates.TOOL_ARGS_PROMPT`
- Workflow：`PromptTemplates.SKILL_ARGS_PROMPT`
- 参数不合法时触发：`PromptTemplates.TOOL_ARGS_REPAIR_PROMPT`

8. 工具执行后，进入语义翻译：
- `DefaultToolSemanticAgent.TOOL_SEMANTIC_PROMPT`
- 产出结构化语义（status/keyFacts/businessImpact/nextStepHint/confidence）

### 阶段C：正式回合（跑主模型）
9. `ChatServiceImpl` 再次调用 pipeline，trigger=`CHAT_TURN`，`runMainModel=true`。

10. `RoundPipelineOrchestratorImpl.executeRound` 中先做 pre-summary：
- `taskOrchestratorService.orchestrateSummary(...)`
- Prompt 来自 `DefaultSummaryAgent.SUMMARY_PROMPT`

11. 进入主模型执行：`TaskOrchestratorServiceImpl.orchestrateMainModel`
- 调用 `contextAssembler.assembleAndSnapshot(...)`
- 这是最终 Prompt 组装关键步骤。

### 阶段D：最终 Prompt 组装策略（核心）
`DefaultContextAssembler` 组装 section 顺序：
1. `Instructions`（`PromptTemplates.SYSTEM_PROMPT`）
2. `Current Task State`
3. `Reconstructed User Intent`
4. `Relevant Knowledge Evidence`
5. `MCP Resource / Prompt Hints`
6. `Tool Evidence`
7. `Recent Interaction Context`
8. `Memory Hints`
9. `Output Constraints`
10. `Runtime Prompt`（`PromptTemplates.RUNTIME_PROMPT` + runtime 输入串）

组装策略要点：
- 不是全量注入，先建 candidate pool，再由 `SemanticPreservingPruner` 做预算裁剪。
- 预算由 `ContextNodeTemplatePolicy` + `tokenBudgetPlan` 联合决定。
- `buildRuntimePromptInput` 把 `normalizedIntent/explicitTaskGoal/missingSlots/intentConfidence/rawInputFactRef` 等压缩为 runtime 输入。
- 最终 Prompt 与 sections 会保存为 `FINAL_MODEL_CONTEXT` 快照，支持回放与审计。

### 阶段E：主模型调用、修复与回写
12. `invokeMainModel` 发送 `LlmMessage.user(finalPrompt)` 到 `LlmClientUtil.generate`。
13. 模型返回后做 JSON 校验：
- 若非合规，触发 `PromptTemplates.REPAIR_PROMPT` 二次修复。
- 仍失败则 fallback。
14. 输出 `replyText`，进入 post-summary + writeRoundState，完成本回合状态闭环。

---

## 5. 正常链路中的 Prompt 安全策略

### 5.1 前置检测
- `LlmClientUtil` 可对用户最近输入执行 `PROMPT_INJECTION_DETECTION`。
- 检测对象是“最近 user 文本”，避免把系统模板本身送入检测。

### 5.2 输入包裹
- user 文本统一包裹为：
  - `<user_input> ... </user_input>`
- 降低“用户文本伪造系统指令”直接影响。

### 5.3 系统提示硬化
- 若消息角色是 `system`，会追加 `SYSTEM_SECURITY_NOTICE`。
- 当前主链路多为 user 单消息模式，因此这条硬化能力在主链路利用有限。

---

## 6. 架构优势（针对 Prompt 工程）
- 有明确的“主 Prompt 组装器”（ContextAssembler），非散乱字符串直推模型。
- 有状态驱动的场景化策略（task state / node kind / relational state）。
- 有结构化输出治理（JSON-only + repair 回路 + fallback）。
- 有可审计快照（FINAL_MODEL_CONTEXT），可做问题回放。
- 工具调用前有签名治理，防止决策输入被旁路修改。

---

## 7. 架构短板与风险（针对你关心的设计层）
1. 角色分层尚未彻底落地
- 主模型调用多为 `LlmMessage.user(prompt)`，system/user 边界在消息级不够硬。

2. Prompt 定义仍分散
- `PromptTemplates` 与多个 Agent 内置模板并存，统一升级和一致性验证成本高。

3. 工具决策模板存在双轨
- `PromptTemplates.TOOL_DECISION_PROMPT` 与 `AgentServiceImpl.buildDecisionPrompt` 并存，易漂移。

4. 输出解析/修复重复实现
- 多个 Agent 各自 `stripFence + parse + fallback`，行为不完全一致。

5. 版本治理缺失
- 快照记录了文本，但缺少模板 ID/version，难以做模板级灰度与回滚分析。

---

## 8. 面向当前代码现实的优化方案（分步落地）

### 第一步：统一注册，不动主链路
- 建 Prompt Registry（可先是枚举/配置对象）：
  - `templateId`
  - `version`
  - `owner`
  - `expectedSchema`
  - `usageMethods`
- 先把现有模板全部登记，解决“可见性”问题。

### 第二步：收敛双轨模板
- 将工具决策模板统一到单一来源。
- `AgentServiceImpl.buildDecisionPrompt` 与 `PromptTemplates.TOOL_DECISION_PROMPT` 二选一。

### 第三步：主链路双消息化
- `DefaultContextAssembler` 拆成：
  - `systemWorkset`（规则/约束）
  - `userWorkset`（本轮输入/证据）
- `invokeMainModel` 改为 `messages=[system,user]`。

### 第四步：统一输出契约组件
- 抽出公共组件：`PromptOutputContractService`
  - `parse`
  - `validate`
  - `repair`
  - `fallback`
- 覆盖主模型、summary、recovery、rerank、tool semantic。

### 第五步：快照补齐版本字段
- 在 `FINAL_MODEL_CONTEXT` payload 增加：
  - `promptTemplateId`
  - `promptTemplateVersion`
  - `assemblerVersion`

### 第六步：补 Prompt 契约测试
- 优先覆盖：
  - 主模型输出 JSON 契约
  - repair 生效路径
  - 工具决策参数生成契约
  - 场景切换（planning/executing/waiting）下 section 组成差异

---

## 9. 结论
- 当前 Prompt 架构不是“单 Prompt 项目”，而是“**状态驱动的多 Prompt 协同编排架构**”。
- 对“正常请求链路”的关键控制点已经具备：输入重构 -> 证据重排 -> 受治理组装 -> 主模型修复 -> 摘要回写。
- 下一阶段架构重点不是“再加模板”，而是“**统一治理与版本化**”：单一真源、消息级角色分层、输出契约统一、快照可回放可对比。
