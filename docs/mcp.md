# 一、先给结论：你当前系统应如何定义迁移目标

你现在不是“从 0 到 1 做 MCP”，而是：

- 已经有：
    - 工具注册中心
    - 技能注册中心
    - 向量检索
    - 规划编排
    - 长短期记忆
    - 本地知识库
    - 审批/任务机制
- 缺的是：
    - **MCP 协议边界**
    - **Host / Client / Server 分层**
    - **tools/resources/prompts 的标准化暴露**
    - **执行从 Host 内部反射迁移到 MCP Server**
    - **skill 概念重新拆分**

所以你的迁移目标不应该是“推翻现在库表和逻辑”，而应该是：

> **将现有中心化、Bean 反射驱动的 Agent 架构，改造成以 MCP Server 为能力提供方、Agent Host 为编排方、PG 为能力缓存与增强索引层的标准 MCP 体系。**

---

---

# 二、基于你现有表，先做一次“现状定位”

你的表大概可以分为 5 类：

---

## A. 用户与上下文数据层
这些本来就不是 MCP 协议对象本身，但可以转化为 resource 或 memory/context provider：

- `user_preference`
- `knowledge_base`
- `luna_memory`
- `schedule_task`

---

## B. 系统日志与执行记录
这些属于系统运行面，不是 MCP 核心对象，但很重要：

- `luna_log`
- `tasks`

---

## C. 工具与技能注册层
这是你现在最接近 MCP 的部分：

- `mcp_tools`
- `mcp_skills`

---

## D. 复杂规划 / workflow / orchestration 层
这一层实际上不是 MCP 的核心协议对象，而是你 Agent Host 的“计划与执行引擎”：

- `plan_instance`
- `plan_blueprint`
- `plan_phase`
- `plan_node`
- `plan_edge`
- `plan_event_log`
- `plan_checkpoint`
- `plan_report`

---

## E. 你现状最关键的问题
从表结构上就能看出来 4 个问题：

### 1. `mcp_tools` / `mcp_skills` 其实不是 MCP Server 暴露，而是 Host 内部注册表
字段中有：

- `bean_name`
- `method_name`

这说明工具执行还是在主应用内部。

---

### 2. skill 与 tool 混在“调用能力”这个层面，但 MCP 里 skill 不是一等标准对象
MCP 更强调：

- tools
- resources
- prompts

而 skill 更适合作为：

- workflow
- prompt 模板
- 或 server 端组合能力

---

### 3. 你的知识、用户偏好、记忆等，本质上应逐步转成 resources/context provider
现在它们主要作为内部数据库与检索使用。

---

### 4. 你的 plan 系列表，已经形成了一套很强的编排引擎
这很好，但它应该被放在：

- **Host 侧 orchestration 层**
  而不是试图“塞进 MCP 里”。

---

# 三、迁移时的总体架构目标

建议你迁移成下面这种分层：

---

## 目标架构

```text
                   ┌──────────────────────────────┐
                   │      Spring Boot Agent Host   │
                   │      + LangChain4j            │
                   │-------------------------------│
User Request ----> │ 1. 会话管理 / 记忆注入         │
                   │ 2. 规划器(Plan Engine)         │
                   │ 3. Tool/Prompt/Resource检索    │
                   │ 4. MCP Client Adapter         │
                   │ 5. 审批/风险控制               │
                   └─────────────┬────────────────┘
                                 │
                ┌────────────────┼─────────────────┐
                │                │                 │
                v                v                 v
      ┌────────────────┐ ┌────────────────┐ ┌────────────────┐
      │ Order MCPServer│ │ CRM MCPServer  │ │ Local MCPServer │
      │----------------│ │----------------│ │-----------------│
      │ tools/list     │ │ tools/list     │ │ tools/list      │
      │ tools/call     │ │ tools/call     │ │ resources/read  │
      │ resources/list │ │ resources/list │ │ prompts/get     │
      │ prompts/list   │ │ prompts/list   │ │ tools/call      │
      └────────────────┘ └────────────────┘ └────────────────┘
```

---

## 各层职责

### 1. Agent Host
保留你现有优势：

- LangChain4j
- 向量检索
- Plan 编排
- 审批
- Memory
- 对话与会话管理

但 **不再直接反射调用 Bean**。

---

### 2. MCP Client Adapter
新增一层，专门负责：

- 与 MCP Server 通讯
- 拉取 tools/resources/prompts
- 发起标准调用

---

### 3. MCP Server
将原来 `bean + method` 的执行逻辑迁到 server 内部。

---

### 4. PG 数据库
从“执行事实源”改造成“缓存与增强索引层”：

- 缓存来自各个 server 的能力描述
- 做 embedding
- 支持检索、权限、版本、审计

---

# 四、迁移原则：哪些保留，哪些重构

---

## 可以保留的

### 1. `knowledge_base`
继续作为本地知识库

### 2. `luna_memory`
继续作为长期记忆

### 3. `user_preference`
继续作为用户画像/偏好

### 4. `schedule_task`
继续做待办、提醒

### 5. `plan_*`
继续做 planner / executor / DAG orchestration

### 6. `tasks`
继续做异步任务、审批流

### 7. LangChain4j
继续作为 host 侧 agent 编排框架

---

## 需要改造的

### 1. `mcp_tools`
从“本地反射注册表”改造成“能力目录缓存 + server内部映射”

### 2. `mcp_skills`
要拆分成：
- prompts
- workflow templates
- composite tools（少量）

### 3. Host 执行机制
从：
- `Bean + method_name + reflection`
  改成：
- `MCP Client -> tools/call`

---

# 五、完整迁移路线图

我建议分 **6 个阶段**。

---

# 阶段 1：统一概念模型，先把 skill/tool/resource/prompt 重新定义

这是整个迁移的第一步，否则越改越乱。

---

## 1.1 重新定义 Tool
适合保留在 MCP tool 的能力：

- 原子动作
- 输入输出清晰
- 尽量同步
- 可标准化 schema
- 例如：
    - 查询订单
    - 创建日程
    - 更新用户偏好
    - 查询知识片段
    - 发送通知

这对应你当前的 `mcp_tools`。

---

## 1.2 重新定义 Skill
你当前 `mcp_skills` 里混合了很多“复杂能力”。

建议拆成 3 类：

### 类型 A：Prompt Template
如果 skill 只是：
- 某个任务模板
- 某个分析模板
- 某个写作模板
- 某个总结模板

那迁到 `prompt`。

---

### 类型 B：Workflow / Plan Template
如果 skill 有：
- `required_capabilities`
- `tool_slots`
- `thought_chain`

这说明它其实是个**工作流模板 / 编排模板**

不应该直接继续作为 MCP skill 暴露，而是迁到：

- `workflow_template`
- 或直接进入你现有 `plan_blueprint`

---

### 类型 C：Composite Tool
极少数 skill 可以保留为 MCP tool，但在 server 侧内部再调多个动作。

比如：
- “生成日报并发送邮件”
- “分析客户并生成跟进建议”

但对 Host 来说，它还是一个 tool。

---

## 1.3 定义 Resource
你现有的以下内容可以逐步 MCP 化成 resources：

- `knowledge_base`
- `user_preference`
- `luna_memory`
- `schedule_task`

资源的特点：

- 可被读取
- 通常不直接执行动作
- 更适合为模型提供上下文

例如：

- `resource://knowledge/article/{id}`
- `resource://memory/session/{sessionId}`
- `resource://user/preferences/{userId}`
- `resource://schedule/today`

---

## 1.4 定义 Prompt
把原先部分 skill 改为 prompts：

比如：
- 会议纪要总结模板
- 销售机会分析模板
- SQL 生成模板
- 风险评估模板

---

# 阶段 2：新增 MCP 核心目录表，不直接删旧表

不要直接推翻 `mcp_tools` / `mcp_skills`。  
建议先**新增标准化目录表**，然后逐步迁移。

---

## 2.1 新增 `mcp_server_registry`
记录有哪些 MCP Server。

```sql
create table mcp_server_registry (
    id bigserial primary key,
    server_code varchar(100) not null unique,
    server_name varchar(200) not null,
    description text,
    base_url varchar(500),
    transport_type varchar(50) not null, -- HTTP/SSE/WS/STDIO
    auth_type varchar(50),
    auth_config jsonb,
    enabled boolean default true,
    health_status varchar(20) default 'UNKNOWN',
    last_sync_at timestamp,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp
);
```

---

## 2.2 新增 `mcp_tool_catalog`
作为 MCP tool 的标准目录缓存表。

```sql
create table mcp_tool_catalog (
    id bigserial primary key,
    server_code varchar(100) not null,
    tool_name varchar(200) not null,
    title varchar(255),
    description text,
    input_schema jsonb not null,
    output_schema jsonb,
    annotations jsonb,
    tags jsonb default '[]'::jsonb,
    enabled boolean default true,
    version varchar(50),
    requires_approval boolean default false,
    sensitivity varchar(50) default 'LOW',
    raw_payload jsonb,
    embedding vector(768),
    synced_at timestamp default current_timestamp,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp,
    unique(server_code, tool_name)
);
```

---

## 2.3 新增 `mcp_prompt_catalog`

```sql
create table mcp_prompt_catalog (
    id bigserial primary key,
    server_code varchar(100) not null,
    prompt_name varchar(200) not null,
    title varchar(255),
    description text,
    arguments_schema jsonb,
    raw_payload jsonb,
    tags jsonb default '[]'::jsonb,
    enabled boolean default true,
    version varchar(50),
    embedding vector(768),
    synced_at timestamp default current_timestamp,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp,
    unique(server_code, prompt_name)
);
```

---

## 2.4 新增 `mcp_resource_catalog`

```sql
create table mcp_resource_catalog (
    id bigserial primary key,
    server_code varchar(100) not null,
    resource_uri varchar(500) not null,
    name varchar(255),
    description text,
    mime_type varchar(100),
    annotations jsonb,
    raw_payload jsonb,
    tags jsonb default '[]'::jsonb,
    enabled boolean default true,
    embedding vector(768),
    synced_at timestamp default current_timestamp,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp,
    unique(server_code, resource_uri)
);
```

---

## 2.5 新增 `mcp_tool_impl_mapping`
这个表只在 **MCP Server 内部** 使用，用于 tool -> bean/service 路由。  
你以前 `mcp_tools` 里的 `bean_name/method_name` 应迁到这里，而不是暴露给 Host。

```sql
create table mcp_tool_impl_mapping (
    id bigserial primary key,
    server_code varchar(100) not null,
    tool_name varchar(200) not null,
    impl_type varchar(50) not null, -- SPRING_BEAN / HTTP / RPC / WORKFLOW
    bean_name varchar(100),
    method_name varchar(100),
    route_uri varchar(500),
    timeout_ms integer default 10000,
    retry_policy jsonb,
    enabled boolean default true,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp,
    unique(server_code, tool_name)
);
```

---

## 2.6 新增 `workflow_template`
把 skill 中编排相关信息单独抽出来。

```sql
create table workflow_template (
    id bigserial primary key,
    workflow_name varchar(200) not null unique,
    description text,
    input_schema jsonb,
    output_schema jsonb,
    required_capabilities jsonb default '[]'::jsonb,
    tool_slots jsonb default '[]'::jsonb,
    thought_chain jsonb default '[]'::jsonb,
    blueprint_json jsonb,
    enabled boolean default true,
    version varchar(50),
    embedding vector(768),
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp
);
```

---

# 阶段 3：旧表到新表的映射方案

这一步是最关键的：**现有数据怎么迁**。

---

## 3.1 `mcp_tools` 迁移策略

你现在的 `mcp_tools`：

- `name`
- `description`
- `bean_name`
- `method_name`
- `input_schema`
- `output_schema`
- `requires_approval`
- `sensitivity`

建议迁移成：

### Host 可见部分 -> `mcp_tool_catalog`
映射：

- `name` -> `tool_name`
- `description` -> `description`
- `input_schema` -> `input_schema::jsonb`
- `output_schema` -> `output_schema::jsonb`
- `version`
- `requires_approval`
- `sensitivity`
- `embedding` -> 改成真正 vector 类型，不要 text

### Server 内部执行部分 -> `mcp_tool_impl_mapping`
映射：

- `bean_name`
- `method_name`

### 注意
如果你当前只有一个应用，先可设置：
- `server_code = 'local-agent-server'`

这样你就能先把现有 tool 伪装成来自一个 MCP Server。

---

## 3.2 `mcp_skills` 迁移策略

你这个表不能直接平移。

建议按以下规则分类迁移：

---

### 规则 1：如果 `thought_chain` / `tool_slots` / `required_capabilities` 明显存在编排逻辑
迁移到 `workflow_template`

映射：

- `name` -> `workflow_name`
- `description`
- `input_schema`
- `output_schema`
- `required_capabilities`
- `tool_slots`
- `thought_chain`

如果它未来会参与 plan 生成，还可以进一步转成 `blueprint_json`。

---

### 规则 2：如果 skill 本质是某个“模型任务模板”
迁移到 `mcp_prompt_catalog`

比如：
- 总结
- 分析
- 分类
- 提取
- 改写

---

### 规则 3：如果 skill 是“一个封装好的复合操作”
迁移成一个 MCP tool，但 server 内部实现改为组合流程。

---

## 3.3 其他现有表与 MCP 的对应关系

---

### `knowledge_base`
建议作为：

- 继续保留内部 RAG 数据源
- 同时暴露部分为 MCP resource

比如：

- `resource://knowledge/{id}`
- `resource://knowledge/source?path=xxx`

另外也可以提供一个 MCP tool：
- `search_knowledge`

因为 resources 适合“按 URI 读取”，而搜索通常更像 tool。

---

### `user_preference`
建议：

- 作为 resource + tool 双形态
- resource:
    - `resource://user/preferences`
- tool:
    - `get_user_preferences`
    - `update_user_preference`

---

### `luna_memory`
建议：

- 继续作为 memory 存储
- 对外如果需要可暴露：
    - resource: `resource://memory/session/{sessionId}`
    - tool: `search_memory`

---

### `schedule_task`
建议：

- tool:
    - `create_schedule_task`
    - `list_schedule_tasks`
    - `complete_schedule_task`
- resource:
    - `resource://schedule/today`
    - `resource://schedule/task/{id}`

---

### `tasks`
这个不属于 MCP 目录对象，它更像**执行状态表**，可以继续保留做：
- 异步工具执行
- 审批流
- 长任务追踪

---

### `plan_*`
全部保留在 Host 侧，不迁入 MCP 核心目录。

但可以考虑有两种增强：

#### 方式 A
Host 继续内部使用 `plan_*`

#### 方式 B
未来如果需要，可单独做一个 `planner-mcp-server`
对外暴露：
- `create_plan`
- `get_plan_status`
- `get_plan_report`

不过这是后话，第一阶段不要做。

---

# 阶段 4：代码架构迁移方案

这部分最重要，因为真正 MCP 化不是改表，而是改执行链路。

---

## 4.1 你当前链路

```text
用户输入
 -> 检索 mcp_tools/mcp_skills
 -> 交给 LLM 决策
 -> LLM 返回 tool/skill + args
 -> Java 应用解析
 -> 反射调用 bean/method
 -> 获取结果
 -> 再喂给模型
 -> 输出
```

---

## 4.2 目标链路

```text
用户输入
 -> Host 检索候选能力（本地PG索引）
 -> 从候选中构造 tool/prompt/resource 上下文
 -> LLM 决策
 -> Host 识别是 tool / prompt / resource / workflow
 -> 若是 tool:
      通过 MCP Client 调 tools/call
 -> 若是 resource:
      通过 MCP Client 调 resources/read
 -> 若是 prompt:
      通过 MCP Client 调 prompts/get
 -> 若是 workflow:
      Host 进入 plan_* 编排执行
 -> 结果回到模型
 -> 输出最终答案
```

---

## 4.3 Host 层新增模块

建议你在 Spring Boot 中新增这些模块接口：

---

### A. `McpClientAdapter`
负责连接多个 MCP Server

```java
public interface McpClientAdapter {

    List<McpToolDescriptor> listTools(String serverCode);

    McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson);

    List<McpPromptDescriptor> listPrompts(String serverCode);

    McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson);

    List<McpResourceDescriptor> listResources(String serverCode);

    McpResourceResult readResource(String serverCode, String resourceUri);
}
```

---

### B. `CapabilityCatalogService`
负责同步 MCP Server 能力到本地数据库：

- 同步 `tools/list`
- 同步 `prompts/list`
- 同步 `resources/list`
- 写入 `mcp_tool_catalog` / `mcp_prompt_catalog` / `mcp_resource_catalog`
- 重新生成 embedding

---

### C. `CapabilityRetrievalService`
替代你现在对 `mcp_tools` / `mcp_skills` 的直接检索：

- 语义检索 tool
- 语义检索 prompt
- 语义检索 resource
- 规则过滤风险、审批、权限

---

### D. `ToolExecutionGateway`
统一执行入口：

```java
public interface ToolExecutionGateway {
    ExecutionResult executeTool(String serverCode, String toolName, String argsJson);
}
```

这个 gateway 内部：
- 做审批检查
- 做 schema 校验
- 做审计日志
- 调 `McpClientAdapter.callTool`

---

### E. `WorkflowOrchestrator`
继续使用你现有 `plan_*` 体系，但输入不再是“skill bean”，而是：
- workflow_template
- tool catalog
- prompt catalog
- resource catalog

---

# 阶段 5：MCP Server 侧迁移方案

这是“真正 MCP 化”的核心。

---

## 5.1 先做一个 Local MCP Server
第一阶段不必拆多个服务。  
你可以先做一个：

- `local-mcp-server`

它内部仍然可以调用你当前 Spring Bean，但对外暴露 MCP 协议。

这样最小成本。

---

## 5.2 Local MCP Server 内部实现方式

### 不要再通过外部传 beanName/methodName
而是 server 启动时构建注册表：

```java
public interface McpToolHandler {
    String toolName();
    String description();
    JsonNode inputSchema();
    JsonNode outputSchema();
    ToolResult call(JsonNode arguments);
}
```

每个 tool 都注册成 handler：

```java
@Component
public class CreateScheduleTaskToolHandler implements McpToolHandler {
    ...
}
```

然后 server 侧维护：

```java
Map<String, McpToolHandler> toolHandlerMap
```

---

## 5.3 为什么不能继续依赖反射作为主执行模式
因为真正 MCP 要做到：

- 工具边界清晰
- schema 明确
- 参数校验前置
- 可跨语言
- 可拆服务
- 安全可控

反射适合作为 server 内部临时兼容层，但不应是外部协议执行模型。

---

## 5.4 MCP Server 最小能力接口
至少支持：

- `tools/list`
- `tools/call`

第二步再补：

- `resources/list`
- `resources/read`
- `prompts/list`
- `prompts/get`

---

# 阶段 6：把你现有数据域逐步 MCP 化

下面给你按你表来做一个“逐表 MCP 化建议”。

---

# 六、逐表迁移建议

---

## 6.1 `mcp_tools`

### 当前问题
- 暴露了 `bean_name`, `method_name`
- `embedding` 类型不统一（text）
- 既是目录又是执行映射

### 建议处理
拆成：

1. `mcp_tool_catalog`
2. `mcp_tool_impl_mapping`

### 迁移动作
- 旧表保留，增加迁移脚本
- Host 检索只查 `mcp_tool_catalog`
- 执行只通过 MCP Client，不再直接读 `bean_name`

### 最终状态
`mcp_tools` 变成历史兼容表，可逐步废弃

---

## 6.2 `mcp_skills`

### 当前问题
`mcp_skills` 混合了：
- 提示模板
- 工作流模板
- 复合能力
- 编排链路描述

### 建议处理
拆成：

1. `mcp_prompt_catalog`
2. `workflow_template`
3. 极少量保留成 composite tool

### 迁移规则建议
你可以写一个分类迁移脚本：

- `run_mode = ASYNC` 且 `thought_chain` 非空 -> `workflow_template`
- `required_capabilities/tool_slots` 非空 -> `workflow_template`
- 只有输入输出和文字描述，没有依赖槽位 -> `prompt`
- 明显是一个业务动作封装 -> `tool`

### 最终状态
`mcp_skills` 逐步废弃，不再作为 Agent 推理时的主对象

---

## 6.3 `knowledge_base`

### 当前角色
RAG 数据源

### MCP 化方式
#### 方式 1：保留内部 RAG
继续用于检索增强

#### 方式 2：增加 MCP resources
暴露：
- `resource://knowledge/{id}`
- `resource://knowledge/source/{hash}`

#### 方式 3：增加 MCP tool
- `search_knowledge(query, topK)`

### 推荐
三者并存：
- 精确读取用 resource
- 检索用 tool
- 大规模召回仍用 Host 内部 RAG

---

## 6.4 `user_preference`

### 当前角色
用户偏好

### MCP 化方式
建议加以下工具：

- `get_user_preferences`
- `update_user_preference`
- `search_user_preference`

资源：
- `resource://user/preferences/current`

---

## 6.5 `luna_memory`

### 当前角色
长期记忆

### MCP 化方式
建议：
- tool:
    - `search_memory`
    - `write_memory`
    - `summarize_memory`
- resource:
    - `resource://memory/session/{sessionId}`
    - `resource://memory/facts/{id}`

但注意：
memory 很多时候不一定需要暴露给所有 server，更多在 Host 使用。

---

## 6.6 `schedule_task`

### 当前角色
提醒、待办、行动任务

### MCP 化方式
工具：
- `create_schedule_task`
- `list_schedule_tasks`
- `update_schedule_task_status`

资源：
- `resource://schedule/today`
- `resource://schedule/task/{id}`

---

## 6.7 `tasks`

### 当前角色
异步任务状态

### MCP 化方式
继续保留，不作为 MCP catalog  
但可用于支持：
- 异步 MCP tool 调用状态跟踪
- 审批等待状态
- 长任务执行状态

你可以未来加：
- `task_type`
- `server_code`
- `tool_name`
- `approval_id`

---

## 6.8 `plan_*`

### 当前角色
Planner / Workflow 引擎

### 迁移结论
**不要迁掉**。  
它不是 MCP 的替代物，而是 MCP Host 的编排层。

### 需要调整的地方
`plan_node.node_type` 当前有：
- ANALYZE
- TOOL
- SKILL
- VALIDATE
- SUMMARIZE
- REPORT
- CODE

建议改造为：

- TOOL
- PROMPT
- RESOURCE
- WORKFLOW
- ANALYZE
- VALIDATE
- REPORT
- CODE

把 `SKILL` 从节点类型中逐步移除。

### 同时增加字段建议
给 `plan_node` 增加：

- `server_code`
- `capability_name`
- `capability_type`
- `resolved_input_json`
- `approval_required`
- `approval_status`

这样节点执行时可以清晰知道调用的是哪个 MCP 能力。

---

# 七、数据库迁移建议清单

下面给你一个明确的数据迁移顺序。

---

## 第一步：新增新表，不动旧表
新增：

- `mcp_server_registry`
- `mcp_tool_catalog`
- `mcp_prompt_catalog`
- `mcp_resource_catalog`
- `mcp_tool_impl_mapping`
- `workflow_template`

---

## 第二步：初始化一个本地 server
插入：

```sql
insert into mcp_server_registry
(server_code, server_name, description, base_url, transport_type, enabled)
values
('local-agent-server', 'Local Agent MCP Server', '本地Spring应用封装出的MCP服务', 'http://localhost:8080/mcp', 'HTTP', true);
```

---

## 第三步：迁移 `mcp_tools`
把 `mcp_tools` 转进：

- `mcp_tool_catalog`
- `mcp_tool_impl_mapping`

同时：
- `embedding text` 重新生成到 `vector(768)` 字段中

---

## 第四步：分类迁移 `mcp_skills`
按规则写迁移程序：
- 一部分进 `workflow_template`
- 一部分进 `mcp_prompt_catalog`
- 少量进 `mcp_tool_catalog`

---

## 第五步：为 `knowledge_base/user_preference/luna_memory/schedule_task` 建立资源映射
不一定真的把所有记录同步到 `mcp_resource_catalog`，你可以先做“可发现资源模板目录”。

比如：
- `resource://knowledge/{id}`
- `resource://memory/session/{sessionId}`
- `resource://user/preferences/current`

目录表里存的是资源类型定义，不一定是每条实体记录。

---

## 第六步：调整 Host 检索入口
原来查：
- `mcp_tools`
- `mcp_skills`

改成查：
- `mcp_tool_catalog`
- `mcp_prompt_catalog`
- `mcp_resource_catalog`
- `workflow_template`

---

# 八、运行时调用流程重构

---

## 8.1 新调用决策流程

### 输入处理
用户输入后，Host 做：

1. 记忆召回：`luna_memory`
2. 偏好召回：`user_preference`
3. 知识召回：`knowledge_base`
4. 能力检索：
    - tool catalog
    - prompt catalog
    - resource catalog
    - workflow template

---

## 8.2 模型可见对象
给 LLM 的候选对象应该长成这样：

### Tool
- name
- description
- input_schema
- approval_required
- sensitivity
- server_code

### Prompt
- name
- description
- arguments_schema
- server_code

### Resource
- uri
- name
- description
- mime_type
- server_code

### Workflow
- workflow_name
- description
- input_schema
- risk level

---

## 8.3 模型输出动作类型
Host 解析模型结果时，不再只处理 `tool/skill`，而处理：

- `tool_call`
- `prompt_get`
- `resource_read`
- `workflow_start`
- `direct_answer`

---

## 8.4 不同动作执行方式

### tool_call
调用 MCP Client -> `tools/call`

### prompt_get
调用 MCP Client -> `prompts/get`

### resource_read
调用 MCP Client -> `resources/read`

### workflow_start
触发你现有 `plan_instance + plan_node`

---

# 九、审批与风险控制怎么迁

你现在 `mcp_tools` 已经有：

- `requires_approval`
- `sensitivity`

这个设计很好，MCP 化后仍然保留，但位置要改变。

---

## 建议规则

### 1. 风险元数据保留在 `mcp_tool_catalog`
字段：

- `requires_approval`
- `sensitivity`

---

### 2. Host 执行前统一拦截
执行某个 tool 前：

- 校验 user role
- 校验 approval policy
- 若需审批：
    - 生成 `tasks`
    - 状态 `PENDING_APPROVAL`
    - 不立即执行 tool

---

### 3. plan_node 也继承风险信息
当 planner 选择某个高风险 tool 时：
- `plan_node.status = APPROVAL_PENDING`

---

# 十、对 plan 系统的改造建议

你的 plan 系统已经很强了，只需要“能力对象标准化”。

---

## 当前问题
`plan_node.node_type` 里还有 `SKILL`

### 建议
逐步将 `SKILL` 淘汰，改成：

- TOOL
- PROMPT
- RESOURCE
- WORKFLOW

---

## 给 `plan_node` 建议新增字段

```sql
alter table plan_node
add column capability_type varchar(50),
add column capability_name varchar(200),
add column server_code varchar(100),
add column resolved_input_json jsonb,
add column approval_required boolean default false,
add column approval_status varchar(50);
```

这样执行器看到节点时就知道：

- 调哪个 server
- 什么能力类型
- 能力名是什么
- 最终参数是什么
- 是否需审批

---

# 十一、兼容期方案

迁移过程中不要一次性切断旧逻辑。  
建议有一个兼容期。

---

## 兼容期双执行模式

### 模式 A：legacy 模式
仍可从 `mcp_tools` / `mcp_skills` 直接反射调用

### 模式 B：mcp 模式
通过 `mcp_tool_catalog` + `McpClientAdapter`

---

## 兼容期策略
可以给每个 tool 增加一个执行模式：

- `execution_mode = LEGACY | MCP`

当迁移完成后，逐步切到 MCP。

---

# 十二、你项目最适合的最小落地版本

如果你希望**2~4 周内可落地一个真正 MCP 雏形**，我建议如下：

---

## 第 1 步：保留一切业务表
保留：
- `knowledge_base`
- `luna_memory`
- `user_preference`
- `schedule_task`
- `tasks`
- `plan_*`

---

## 第 2 步：新增 5 张表
新增：
- `mcp_server_registry`
- `mcp_tool_catalog`
- `mcp_prompt_catalog`
- `mcp_resource_catalog`
- `mcp_tool_impl_mapping`

---

## 第 3 步：把 `mcp_tools` 全量迁成 `mcp_tool_catalog + impl_mapping`
这是最重要的一步。

---

## 第 4 步：先不处理全部 skills，只先把一部分 skill 迁成 workflow_template
优先迁那些明显是“编排模板”的 skill。

---

## 第 5 步：实现一个 Local MCP Server
只支持：
- `tools/list`
- `tools/call`

Host 改成调用 MCP，不再直接反射。

---

## 第 6 步：再加 resources 和 prompts
第二阶段再上：
- `resources/list/read`
- `prompts/list/get`

---

# 十三、推荐的最终表结构分层

你未来数据库最好分成这几层：

---

## 1. 业务数据层
- `user_preference`
- `knowledge_base`
- `luna_memory`
- `schedule_task`

---

## 2. MCP 能力目录层
- `mcp_server_registry`
- `mcp_tool_catalog`
- `mcp_prompt_catalog`
- `mcp_resource_catalog`

---

## 3. MCP Server 执行映射层
- `mcp_tool_impl_mapping`

---

## 4. 编排模板层
- `workflow_template`
- `plan_blueprint`

---

## 5. 运行态编排层
- `plan_instance`
- `plan_phase`
- `plan_node`
- `plan_edge`
- `plan_event_log`
- `plan_checkpoint`
- `plan_report`

---

## 6. 审计与异步执行层
- `tasks`
- `luna_log`

---

# 十四、你当前系统迁移后的角色定位

这是一个很重要的结论：

---

## 你的 Spring Boot 主应用应该变成
**MCP Host + Planner + Memory/RAG + Governance 中心**

即负责：

- 用户交互
- 记忆召回
- 知识召回
- 规划
- 审批
- 能力筛选
- 结果整合

---

## 你的业务能力应该变成
**MCP Server 提供方**

每个 server：
- 暴露 tools/prompts/resources
- 内部可继续用 Spring Bean/Service/DAO

---

## 你的 PG 应该变成
**能力缓存 + 索引增强 + 运行时状态存储**

而不是 Host 的直接执行注册表。

---

# 十五、迁移中的几个重点坑

---

## 1. 不要把 MCP 当成“仅仅换一张注册表”
核心在执行边界，而不是表结构。

---

## 2. 不要把所有 skill 都强行保留
很多 skill 其实是：
- prompt
- workflow
- plan template

---

## 3. 不要让 Host 继续知道 bean_name / method_name
这是最重要的隔离点。

---

## 4. 不要把 resources 理解成“数据库行”
resource 更像“可读取上下文对象”，不一定每条记录都要注册。

---

## 5. 不要试图让 plan 系统变成 MCP 的一部分
plan 是 Host 编排层，不是 MCP 协议本体。

---

# 十六、给你的完整迁移方案总结版

我把上面收敛成一版最终方案：

---

## 目标
将现有 `JSON注册 + PG检索 + 反射Bean执行` 的中心化 Agent 架构，迁移为：

- **Host：LangChain4j + Planner + Governance**
- **Server：MCP tools/resources/prompts provider**
- **PG：catalog cache + vector retrieval + runtime store**

---

## 核心改造点

### 1. 表结构层
新增：
- `mcp_server_registry`
- `mcp_tool_catalog`
- `mcp_prompt_catalog`
- `mcp_resource_catalog`
- `mcp_tool_impl_mapping`
- `workflow_template`

保留：
- `knowledge_base`
- `user_preference`
- `luna_memory`
- `schedule_task`
- `tasks`
- `plan_*`
- `luna_log`

弱化/废弃：
- `mcp_tools`
- `mcp_skills`

---

### 2. 执行链路层
从：
- Host 内部反射调用 Bean

改成：
- Host -> MCP Client -> MCP Server -> Handler -> Service

---

### 3. skill 拆分层
- 原子动作 -> tool
- 模板能力 -> prompt
- 编排能力 -> workflow_template / plan blueprint
- 上下文对象 -> resource

---

### 4. 检索层
保留向量检索，但用途改为：
- **能力发现增强**
  而不是执行事实源

---

### 5. 编排层
保留 `plan_*`，但把节点执行对象改成：
- tool / prompt / resource / workflow

---

### 6. 审批治理层
继续用：
- `requires_approval`
- `sensitivity`
- `tasks`
- `luna_log`

并在 Host 统一拦截。

---

# 十七、我建议你下一步立即做的事情

按优先级：

### P1
先设计并创建下面 5 张表：
- `mcp_server_registry`
- `mcp_tool_catalog`
- `mcp_prompt_catalog`
- `mcp_resource_catalog`
- `mcp_tool_impl_mapping`

### P2
把 `mcp_tools` 迁移到 `mcp_tool_catalog + mcp_tool_impl_mapping`

### P3
实现一个本地 `Local MCP Server`，先只支持：
- `tools/list`
- `tools/call`

### P4
Host 侧新增 `McpClientAdapter`
让 LangChain4j 的 tool 调用最终走 MCP

### P5
把 `mcp_skills` 分类迁移：
- workflow_template
- prompt_catalog
- 少量 tool_catalog

### P6
修改 `plan_node`，将 `SKILL` 逐步替换为：
- TOOL / PROMPT / RESOURCE / WORKFLOW

---
