# Prompt 规范冲突统一说明

更新时间：2026-04-08

## 1. Prompt Key 规范（对应问题8）
- 权威规范：`category.subCategory.name_vX`（长 key）。
- 兼容策略：运行时保留对短 key 的别名匹配（例如 `maid_gentle_v1` 可匹配 `persona.maid.gentle_v1`），避免存量数据失效。
- 新增/治理建议：新建条目统一使用长 key。

## 2. Resolver 调用链（对应问题9）
- 主聊天链路：`DefaultContextAssembler -> PromptResolverService -> slot/section 装配`。
- 工具决策链路：`TaskOrchestrator(或节点编排) -> PromptResolverService -> 工具侧上下文注入`。
- 说明：两条链路都允许调用 Resolver，但主链路以 Assembler 内部调用为准。

## 3. 分类查询返回契约（对应问题10）
- 兼容保留：`GET /api/prompt/categories` 继续返回 `List<String>`。
- 元信息增强：新增 `GET /api/prompt/categories/detail`，返回分类元信息：
  - `categoryKey`
  - `categoryName`
  - `sortOrder`
  - `keywordMatchAllowed`
  - `executionCategory`
  - `enabled`

## 4. 执行型 Prompt 装配口径（对应问题4）
- 统一口径：执行型分类允许 `ALWAYS`，但仅限稳定基线类条目（`system` / `guardrail` / `format`）。
- 显式匹配约束：执行型中具备动作或阶段语义的条目（如 `tool` / `repair` / `summary` / `agent-local` / `task`）必须使用显式匹配模式（`AGENT_ONLY` / `KEYWORD_AND_AGENT` / `KEYWORD_OR_AGENT` / `POLICY_ONLY` / `MANUAL_ONLY`），不建议使用 `ALWAYS`。
- 验收判定：代码保持现状（允许执行型使用 `ALWAYS`），治理层面按上述分类约束进行审核与配置。
