# Luna 项目 MCP 架构设计与调用全流程

## 1. 架构整体设计（基于当前代码）

### 1.1 分层结构

1. API 层（MCP 对外入口）
- `src/main/java/org/yilena/luna/controller/McpController.java`
- 路由前缀：`/mcp`
- 同时提供两类接口：
  - 兼容 CRUD：`/tools`、`/skills`、`/resources`
  - MCP 风格协议：`/tools/list`、`/tools/call`、`/prompts/list`、`/prompts/get`、`/resources/list`、`/resources/read`

2. 服务层（编目与协议编排）
- `src/main/java/org/yilena/luna/service/impl/McpServiceImpl.java`
- 职责：
  - Tool/Skill 注册更新删除
  - 统一资源聚合检索（Tool/Prompt/Resource/Workflow）
  - 将协议调用委托给 `McpClientAdapter`
  - 从 `json/tool`、`json/skill` 同步编目

3. 客户端适配层（Host 到 MCP Provider）
- 接口：`McpClientAdapter`
- 本地实现：`LocalMcpClientAdapter`
- 当前实现特点：
  - `list*` 直接读 catalog 表
  - `callTool` 通过 `mcp_tool_impl_mapping` 找到 `bean+method`
  - 执行器为 `ReflectionToolExecutor`（仅支持 `SPRING_BEAN`）

4. 执行与风控层
- `McpToolExecutionGateway`：tool 参数 schema 校验、审批判定、执行封装
- `ExecutionGate`：统一风险检查入口
- `ApprovalService`：审批任务创建与审批后续执行
- `ReflectionToolExecutor`：最终反射调用 Spring Bean 方法

5. Skill 编排层
- `SkillExecutor`
- 通过 `toolSlots + thoughtChain` 按步骤执行
- 每步按 capability 检索 tool（`mcpService.searchResources`），并通过 `ToolExecutionGateway` 执行

6. 存储层（PostgreSQL + PGVector）
- 目录编目：`mcp_tool_catalog`、`mcp_prompt_catalog`、`mcp_resource_catalog`
- 执行路由：`mcp_tool_impl_mapping`
- 服务注册：`mcp_server_registry`
- 工作流模板：`workflow_template`
- 兼容历史表：`mcp_tools`、`mcp_skills`

### 1.2 当前关键设计点

1. `serverCode` 统一默认值：`local-agent-server`（`McpConstant.LOCAL_SERVER_CODE`）。
2. Tool 的“可发现信息”和“执行实现路由”已拆分：
- 可发现：`mcp_tool_catalog`
- 可执行：`mcp_tool_impl_mapping`
3. Skill 在当前实现中有两种落点：
- 编排型 Skill（ASYNC/有 toolSlots 等）-> `workflow_template`
- 普通 Skill -> `mcp_prompt_catalog`
4. MCP 调用目前是“本地 Provider 模式”（`LocalMcpClientAdapter`），尚未切换到远程 HTTP/WS Provider。

---

## 2. 如何注册 Tool

### 2.1 API 注册（推荐）

接口：`POST /mcp/tools`  
核心字段：
- `name`
- `description`
- `beanName`
- `methodName`
- `inputSchema`
- `outputSchema`
- `requiresApproval`
- `sensitivity`

注册后动作（`McpServiceImpl.registerTool`）：
1. Upsert `mcp_tool_catalog`（语义信息、schema、风险字段、embedding）。
2. Upsert `mcp_tool_impl_mapping`（`impl_type=SPRING_BEAN`，写入 `bean_name/method_name`）。

### 2.2 JSON 批量注册

接口：`POST /mcp/catalog/sync`  
逻辑：
1. 扫描 `json/tool/*.json`
2. 映射为 `McpTool`
3. 内部调用 `registerTool`

示例文件：`json/tool/web_search.json`

---

## 3. 如何注册 Skill

### 3.1 API 注册

接口：`POST /mcp/skills`  
核心字段：
- 基础：`name/description/version/inputSchema/outputSchema/runMode`
- 编排：`requiredCapabilities/toolSlots/thoughtChain`
- 兼容字段：`beanName/methodName`

注册后分流（`McpServiceImpl.registerSkill`）：
1. 若是 workflow 型 Skill（ASYNC 或存在编排字段）：
- Upsert 到 `workflow_template`
2. 否则：
- Upsert 到 `mcp_prompt_catalog`

### 3.2 JSON 批量注册

接口同样是：`POST /mcp/catalog/sync`  
逻辑：
1. 扫描 `json/skill/*.json`
2. 映射为 `McpSkill`
3. 根据规则落到 `workflow_template` 或 `mcp_prompt_catalog`

示例文件：`json/skill/skill_search_ingest_kb.json`

---

## 4. 一次正常 MCP 调用全流程（Tool Call）

以下以 `POST /mcp/tools/call` 为例：

1. 请求进入 `McpController.callTool`
- 请求体：`serverCode`、`toolName`、`argumentsJson`

2. 转发到 `McpServiceImpl.callTool`
- 直接委托 `mcpClientAdapter.callTool(...)`

3. 进入 `LocalMcpClientAdapter.callTool`
- 解析 `serverCode`（空则默认 `local-agent-server`）
- 查询 `mcp_tool_impl_mapping`：`findEnabledMapping(serverCode, toolName)`

4. 路由决策
- 未找到映射：返回 `TOOL_MAPPING_NOT_FOUND`
- `impl_type` 不是 `SPRING_BEAN`：返回 `UNSUPPORTED_IMPL`
- 找到且可执行：进入反射执行

5. 执行器调用
- `ReflectionToolExecutor.executeInternal(beanName, methodName, argsJson)`
- 从 Spring 容器取 Bean
- 找目标方法
- 按方法参数名或 `@RequestParam` 解析 `argumentsJson`
- 反射执行并返回 JSON 字符串结果

6. 结果封装
- `LocalMcpClientAdapter` 解析 raw JSON 到 `Map`
- 提取 `status`（默认 success）
- 组装 `McpToolCallResult` 返回给上层

7. Controller 返回 HTTP 响应
- 响应中包含：`status/serverCode/toolName/data/rawResult`

---

## 5. 补充：在 Agent/Skill 中触发 MCP 调用

1. `AgentServiceImpl` 决策到某个 TOOL 后，不直接反射，走 `ToolExecutionGateway`。  
2. `McpToolExecutionGateway` 会先做：
- input schema 校验
- 风险/审批拦截（必要时抛审批中断）
3. 通过 `mcpClientAdapter.callTool` 发起真实 MCP 调用。  
4. Skill 场景下（`SkillExecutor`）会按 `toolSlots` 逐步重复上述流程。

---

## 6. 快速排障清单

1. `tools/call` 报 `TOOL_MAPPING_NOT_FOUND`
- 检查 `mcp_tool_impl_mapping` 是否有 `server_code + tool_name` 且 `enabled=true`

2. 报 `UNSUPPORTED_IMPL`
- 当前仅支持 `SPRING_BEAN`

3. 调用成功但参数为空
- 检查方法参数名是否可被反射识别，或显式加 `@RequestParam("xxx")`

4. Skill 执行失败提示缺少工具槽位
- 检查 skill 的 `toolSlots` 与 `requiredCapabilities` 是否完整、是否一致

