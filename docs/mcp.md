# Luna MCP 架构（基于当前代码实现）

## 1. 文档目标
本文基于当前仓库代码，给出 MCP 相关架构的“现状版”说明，覆盖：
1. 架构设计与分层边界
2. 每个运行阶段的职责和作用
3. 一次完整调用链（Chat 主链路 + Plan 编排链路）
4. 关键数据表与当前实现约束

---

## 2. 当前架构总览

```text
Client
  -> /luna/api/chat/*, /luna/api/plan/*, /mcp/*
      -> Controller 层
          -> Service 层（Chat/Mcp/Plan/Approval）
              -> Router + LLM 决策 + Gateway（风控/审批/schema）
                  -> McpClientAdapter（当前是 LocalMcpClientAdapter）
                      -> mcp_* catalog + mcp_tool_impl_mapping
                          -> ReflectionToolExecutor
                              -> Spring Bean Method
```

当前是“Host 与 Provider 同进程”的本地形态：
1. Host 通过 `McpClientAdapter` 发起 MCP 调用。
2. `LocalMcpClientAdapter` 直接查本地 catalog，并通过 `mcp_tool_impl_mapping` 映射到 Spring Bean 执行。
3. 协议层已抽象为 MCP 风格（tools/prompts/resources），但底层执行仍是本地反射。

---

## 3. 分层与职责

### 3.1 API 层
核心入口：
1. `ChatController` (`/luna/api/chat/*`)
2. `PlanOrchestratorController` (`/luna/api/plan/*`)
3. `McpController` (`/mcp/*`)
4. `ApprovalController` (`/mcp/skills/approval`)

职责：
1. 接收请求并做基础参数校验
2. 将请求路由到对应服务
3. 返回结构化响应（JSON）

### 3.2 MCP 服务层
核心类：`McpServiceImpl`

职责：
1. 兼容旧注册接口（tool/skill CRUD）
2. 对外聚合统一资源模型 `Resource`（TOOL/PROMPT/RESOURCE/WORKFLOW）
3. 对内委托 `McpClientAdapter` 执行 MCP 风格操作
4. 执行 `json/tool`、`json/skill` 到 catalog 的同步

### 3.3 MCP Client 适配层
核心接口/实现：`McpClientAdapter` / `LocalMcpClientAdapter`

职责：
1. `listTools/listPrompts/listResources`
2. `callTool/getPrompt/readResource`
3. 统一 `serverCode`（默认 `local-agent-server`）

当前实现特征：
1. `list*` 读取 `mcp_*_catalog`
2. `callTool` 读取 `mcp_tool_impl_mapping`
3. 仅支持 `impl_type=SPRING_BEAN`

### 3.4 执行治理层
核心类：`McpToolExecutionGateway` + `ExecutionGate`

职责：
1. 工具入参 schema 校验
2. 风险检查与审批分流
3. 统一执行返回结构 `ExecutionResult`

### 3.5 审批层
核心类：`ApprovalServiceImpl`

职责：
1. 创建审批任务并中断执行（抛出 `NeedApprovalException`）
2. Redis 暂存任务，SSE 推送前端
3. 审批通过后重新调用 `mcpService.callTool` 并续跑对话

### 3.6 编排层（Skill/Plan）
1. `SkillExecutor`：基于 `toolSlots + thoughtChain` 逐步执行
2. `PlanOrchestratorServiceImpl`：计划生命周期（创建、执行、收尾、报告）
3. `PhaseExecutionServiceImpl`：DAG 拓扑分批执行节点

---

## 4. 运行阶段职责（MCP 主链路）

### 阶段 1：能力建模与入库
职责：把工具/提示词/资源/工作流统一建模并存入 catalog。  
作用：给检索与路由提供可发现能力清单。

### 阶段 2：候选能力检索
职责：根据输入从 `McpService.searchResources` 检索候选。  
作用：缩小 LLM 决策范围，控制误调用。

### 阶段 3：LLM 决策与参数生成
职责：让模型输出目标能力名与参数 JSON。  
作用：把自然语言意图转为结构化可执行请求。

### 阶段 4：参数修复与校验
职责：对参数进行 schema 校验与必要修复。  
作用：降低调用失败率，统一输入契约。

### 阶段 5：风控与审批闸门
职责：检查敏感级别、审批策略。  
作用：高风险动作先审批后执行。

### 阶段 6：MCP 协议调用
职责：通过 `McpClientAdapter` 执行 `tools/call`、`prompts/get`、`resources/read`。  
作用：统一 Host 到能力提供方的调用面。

### 阶段 7：执行路由与落地
职责：`mcp_tool_impl_mapping` 路由到具体 Bean/Method，反射执行。  
作用：把协议调用真正落地为业务代码执行。

### 阶段 8：结果回流
职责：执行结果回传 chat/plan，落会话、落事件、SSE 推送。  
作用：形成可观测、可追踪、可继续编排的闭环。

---

## 5. 一次完整调用链（Chat -> Tool）

### 5.1 入口
`POST /luna/api/chat/message` -> `ChatServiceImpl.chat`

### 5.2 上下文构建
1. 检索 RAG（知识/偏好/长期记忆）
2. 读取近期会话
3. 组装 `ToolCallingContext`（ThreadLocal）

### 5.3 能力决策
1. `AgentServiceImpl.processToolCalling`
2. `ToolRouter.findCandidates` -> `McpService.searchResources`
3. LLM 输出目标能力名（tool/prompt/resource/workflow）
4. LLM 生成参数并做 schema 修复

### 5.4 执行分流
1. `WORKFLOW/SKILL` -> `SkillExecutor`
2. `PROMPT` -> `mcpService.getPrompt`
3. `RESOURCE` -> `mcpService.readResource`
4. `TOOL` -> `ToolExecutionGateway.executeTool`

### 5.5 Tool 执行细链路
1. `McpToolExecutionGateway` 做 schema + 审批检查
2. `mcpClientAdapter.callTool(serverCode, toolName, args)`
3. `LocalMcpClientAdapter` 查询 `mcp_tool_impl_mapping`
4. `ReflectionToolExecutor.executeInternal(beanName, methodName, argsJson)`
5. 反射调用 Spring Bean 方法并返回 JSON

### 5.6 结果回写
1. 返回工具结果给 ChatService
2. 进入最终回复组装（LLM）
3. 落会话 + SSE 状态更新
4. 返回前端

---

## 6. 一次完整调用链（Plan -> Phase -> Node）

### 6.1 入口
`POST /luna/api/plan/run` -> `PlanOrchestratorServiceImpl.createAndRunPlan`

### 6.2 蓝图生成
1. `MasterPlanningService.generateBlueprint`
2. 校验蓝图
3. 落库：`plan_instance/plan_phase/plan_node/plan_edge`

### 6.3 阶段执行
1. `PhaseExecutionService.executePhase`
2. 基于 DAG 拓扑排序拆批次
3. 批次内并行（虚拟线程）执行节点

### 6.4 节点执行
1. 构建 nodeGoal
2. 调用 `AgentService.processToolCalling`
3. 复用 Chat 同一能力调用链（含 MCP 调用、审批）
4. 回写 node 状态、输出、事件

### 6.5 收尾
1. 统计阶段与节点结果
2. `finalizeAndReport` 生成报告
3. 推送计划完成事件

---

## 7. 数据层角色划分

### 7.1 MCP 新目录表
1. `mcp_server_registry`：服务注册
2. `mcp_tool_catalog`：工具目录（发现面）
3. `mcp_prompt_catalog`：提示词目录
4. `mcp_resource_catalog`：资源目录
5. `mcp_tool_impl_mapping`：工具实现路由（执行面）
6. `workflow_template`：从 skill 拆分出的编排模板

### 7.2 兼容旧表
1. `mcp_tools`
2. `mcp_skills`

现状：兼容接口还在，但执行主路径已更多依赖 `mcp_*_catalog` 与 `workflow_template`。

### 7.3 编排运行时
1. `plan_instance/plan_phase/plan_node/plan_edge`
2. `plan_event_log/plan_checkpoint/plan_report`
3. `tasks`（异步/审批状态承载）

---

## 8. 当前架构结论（现状扫描）

1. 协议形态：已具备 MCP 风格接口（tools/prompts/resources）。
2. 执行形态：仍是本地映射 + 反射执行（非远程 provider）。
3. 治理能力：已有 schema 校验、敏感度、审批中断与续跑。
4. 编排能力：Skill + Plan 两套编排链路并存，Plan 已支持 DAG/分批并行/事件可观测。
5. 演进方向：可在不改 Host 上层的前提下，将 `LocalMcpClientAdapter` 逐步替换为远程 MCP Provider 适配器。

---

## 9. 推荐维护原则（针对当前代码）

1. 继续保持“发现面（catalog）与执行面（impl mapping）分离”。
2. 新能力优先走 `mcp_*_catalog`，避免回退到旧表直连。
3. 任何高风险 tool 必须经过 `ToolExecutionGateway`，不要绕过审批链路。
4. Plan 节点类型优先使用 `TOOL/PROMPT/RESOURCE/WORKFLOW`，逐步减少 legacy `SKILL`/`SUMMARIZE`。
