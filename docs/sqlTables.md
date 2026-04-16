# Luna 项目数据库表结构说明

> 本文档基于 `src/main/resources/sql/luna/public/*.sql` 中的 `CREATE TABLE` 语句以及对应的实体类（`@TableName` / `@Table` 注解）提取、比对而成。所有字段均标注了数据类型、业务含义、职责及关键特性（主键、外键、唯一、索引、默认值、逻辑删除等），并已按业务模块进行分组。

---

## 📦 1️⃣ OpenClaw 编排（任务流）

### 表名：`plan_instance`
- **用途**：记录一次完整的计划实例（对应一次用户目标），存储会话、目标、约束、状态等信息。
- **职责**：计划的根对象，所有后续阶段、节点、日志均以该表的 `plan_id` 为关联键。
- **表类型**：核心表

#### 字段说明
- `plan_id`：`varchar(64)` **主键** – 实例唯一标识。
- `session_id`：`varchar(64)` – 所属会话，关联 `agent_session.session_id`（外键）。
- `user_goal`：`text` – 用户原始目标描述。
- `constraints_json`：`jsonb` – 计划约束（结构化 JSON）。
- `success_criteria`：`text` – 成功标准。
- `planning_model`：`varchar(64)` – 生成蓝图的模型标识。
- `plan_version`：`integer` – 蓝图版本号。
- `status`：`varchar(32)` – 计划状态（PENDING、RUNNING、SUCCESS、FAILED 等）。
- `current_loop_index`：`integer` – 循环执行的当前轮次（用于重试）。
- `final_status`：`varchar(32)` – 计划结束时的最终状态。
- `error_message`：`text` – 失败时的错误信息。
- `started_at`、`finished_at`：`timestamp` – 开始/结束时间。
- `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP** – 自动填充（`INSERT`、`INSERT_UPDATE`）。

---

### 表名：`plan_phase`
- **用途**：将计划拆分为若干阶段，每个阶段包含节点列表、入口/出口条件等。
- **职责**：阶段调度与状态管理。
- **表类型**：核心表

#### 字段说明
- `phase_id`：`varchar(64)` **主键**。
- `plan_id`：`varchar(64)` – 所属计划（外键 → `plan_instance.plan_id`）。
- `phase_order`：`integer` – 阶段顺序号。
- `name`：`varchar(255)` – 阶段名称。
- `objective`：`text` – 阶段目标描述。
- `node_ids`：`jsonb` – 本阶段包含的节点 ID 列表。
- `entry_criteria`、`exit_criteria`：`text` – 入口/出口条件。
- `status`：`varchar(32)` – 阶段状态（PENDING、RUNNING、SUCCESS、FAILED）。
- `started_at`、`finished_at`：`timestamp`。
- `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**（自动填充）。

---

### 表名：`plan_node`
- **用途**：计划图的单个执行节点，描述节点类型、绑定能力、输入/输出、执行状态等。
- **职责**：执行单元的元数据与运行时状态。
- **表类型**：核心表

#### 字段说明
- `node_id`：`varchar(64)` **主键**。
- `plan_id`：`varchar(64)` – 所属计划（外键 → `plan_instance.plan_id`）。
- `phase_id`：`varchar(64)` – 所属阶段（外键 → `plan_phase.phase_id`）。
- `name`：`varchar(255)` – 节点名称。
- `node_type`：`smallint` – 节点类型枚举（0‑ANALYZE,1‑TOOL,3‑VALIDATE,5‑REPORT,6‑CODE,7‑PROMPT,8‑RESOURCE,9‑WORKFLOW）。
- `capability_type`、`capability_name`：`varchar` – 绑定的能力（工具、工作流等）。
- `server_code`：`varchar(100)` – 调用的服务编码。
- `input_json`、`resolved_input_json`：`jsonb` – 原始输入与解析后输入。
- `expected_output_schema`：`jsonb` – 预期输出结构（用于校验）。
- `dependencies`：`jsonb` – 前置依赖节点 ID 列表。
- `parallel_group`：`varchar(64)` – 并行分组标识。
- `status`：`smallint` **默认 0** – 节点状态编码（0‑PENDING,1‑RUNNING,2‑SUCCESS,3‑FAILED,4‑BLOCKED,5‑APPROVAL_PENDING,6‑SKIPPED）。
- `approval_required`：`boolean` **默认 false** – 是否需人工审批。
- `approval_status`：`varchar(50)` – 审批状态（PENDING、APPROVED、REJECTED）。
- `retry_policy`：`jsonb` – 重试策略。
- `retry_count`、`max_retry`：`integer` **默认 0** – 已重试次数 / 最大重试次数。
- `model_hint`：`smallint` – 模型规模提示（0‑SMALL,1‑MID,2‑BIG,3‑FLASH）。
- `resource_hint`：`jsonb` – 资源选择提示。
- `output_json`、`output_for_next`：`jsonb` – 节点原始输出 / 供下游节点使用的裁剪后输出。
- `fail_reason`：`text` – 失败原因摘要。
- `last_error_stack_brief`：`text` – 最近异常栈简要。
- `risk_level`：`smallint` **默认 0** – 风险等级（0‑LOW,1‑MEDIUM,2‑HIGH）。
- `cost_ms`：`bigint` – 节点执行耗时（毫秒）。
- `started_at`、`finished_at`：`timestamp`。
- `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**（自动填充）。

---

### 表名：`plan_edge`
- **用途**：描述节点之间的有向连线，支持条件表达式用于流程分支。
- **职责**：构建计划的有向图结构。
- **表类型**：关联表

#### 字段说明
- `id`：`bigserial` **主键**。
- `plan_id`：`varchar(64)` – 所属计划（外键 → `plan_instance.plan_id`）。
- `from_node_id`：`varchar(64)` – 起始节点（外键 → `plan_node.node_id`）。
- `to_node_id`：`varchar(64)` – 目标节点（外键 → `plan_node.node_id`）。
- `condition_expr`：`text` – 条件表达式（用于分支）。
- `created_at`：`timestamp` **默认 CURRENT_TIMESTAMP**。

---

### 表名：`plan_blueprint`
- **用途**：全局规划蓝图（按版本存档），保存完整的 JSON（包含 phase / node / edge / risk）。
- **职责**：蓝图的版本化管理与回溯。
- **表类型**：核心表（版本化）

#### 字段说明
- `id`：`bigserial` **主键**。
- `plan_id`：`varchar(64)` – 关联 `plan_instance.plan_id`（`ON DELETE CASCADE`）。
- `plan_version`：`integer` **NOT NULL** – 蓝图版本号。
- `blueprint_json`：`jsonb` **NOT NULL** – 完整蓝图 JSON。
- `generated_by_model`：`varchar(64)` – 生成模型标识。
- `generated_at`：`timestamp` **NOT NULL** – 生成时间（`DEFAULT CURRENT_TIMESTAMP`）。
- `created_at`：`timestamp` **NOT NULL** – 创建时间（`DEFAULT CURRENT_TIMESTAMP`）。
- **唯一约束**：`UNIQUE (plan_id, plan_version)` 防止同计划同版本重复。

---

### 表名：`plan_checkpoint`
- **用途**：运行时快照，用于恢复、回滚或可视化回放。
- **职责**：在关键节点保存状态快照，配合 `plan_blueprint` 实现增量恢复。
- **表类型**：关联表（快照）

#### 字段说明
- `checkpoint_id`：`varchar(64)` **主键**（业务生成）。
- `plan_id`：`varchar(64)` – 所属计划（外键 → `plan_instance.plan_id`，`ON DELETE CASCADE`）。
- `phase_id`、`node_id`：`varchar(64)` – 所在阶段/节点。
- `checkpoint_data`：`jsonb` – 快照数据（上下文、变量等）。
- `snapshot_hash`：`varchar(128)` – 快照哈希，用于防篡改。
- `created_by`：`varchar(64)` – 创建者（用户或系统）。
- `created_at`：`timestamp` **默认 CURRENT_TIMESTAMP**。

---

### 表名：`plan_report`
- **用途**：计划执行结束后生成的报告（标题、摘要、文件路径、状态等）。
- **职责**：向前端或外部系统提供统一的执行报告。
- **表类型**：日志/报告表

#### 字段说明
- `report_id`：`bigserial` **主键**。
- `plan_id`：`varchar(64)` – 所属计划。
- `session_id`：`varchar(64)` – 所属会话。
- `final_status`：`varchar(32)` – 报告的最终状态（SUCCESS / FAILED）。
- `report_title`：`varchar(255)` – 报告标题。
- `summary`：`text` – 报告摘要。
- `report_path`：`varchar(255)` – 本地文件系统路径。
- `report_url`：`varchar(255)` – 对外访问 URL。
- `open_result`：`text` – 打开报告后返回的业务结果（可选）。
- `report_html`：`text` – 完整 HTML 内容（便于前端直接渲染）。
- `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**（自动填充）。

---

### 表名：`plan_event_log`
- **用途**：计划运行过程中的事件日志（状态变更、错误、审计等），支持多级别（INFO / WARN / ERROR）。
- **职责**：统一审计、回放与监控。
- **表类型**：日志表

#### 字段说明
- `event_id`：`bigserial` **主键**。
- `plan_id`：`varchar(64)` – 关联计划。
- `phase_id`、`node_id`：`varchar(64)` – 可选关联阶段/节点。
- `event_type`：`smallint` **NOT NULL** – 事件类型枚举（0‑INFO,1‑WARN,2‑ERROR …）。
- `event_payload`：`jsonb` – 事件详情（结构化）。
- `trace_id`：`varchar(128)` – 链路追踪 ID（跨服务）。
- `level`：`smallint` **NOT NULL** – 级别（INFO/WARN/ERROR）。
- `created_at`：`timestamp` **默认 CURRENT_TIMESTAMP**。

---

## 📦 2️⃣ CodeOps / MCP（工具/能力管理）

### 表名：`mcp_tool_catalog`
- **用途**：中心化的工具/能力元数据（名称、描述、输入/输出 schema、标签等）。
- **职责**：统一注册、查询与权限控制。
- **表类型**：核心配置表

#### 字段说明
- `id`：`bigserial` **主键**。
- `server_code`：`varchar(100)` – 所属服务器。
- `tool_name`：`varchar(200)` – 工具唯一标识。
- `title`：`varchar(255)` – 可读标题。
- `description`：`text` – 业务描述。
- `input_schema`、`output_schema`：`jsonb` – 输入/输出 JSON Schema。
- `annotations`、`tags`：`jsonb` – 额外注解、标签集合。
- `enabled`：`boolean` – 是否启用。
- `version`：`varchar(32)` – 版本号。
- `execution_mode`：`varchar(32)` – 执行模式（SYNC/ASYNC）。
- `requires_approval`：`boolean` – 是否需要审批。
- `sensitivity`：`varchar(32)` – 敏感度等级。
- `raw_payload`：`jsonb` – 原始配置（便于回滚）。
- `embedding`：`vector(768)` – 向量化表示（用于检索/相似度）。
- `synced_at`、`created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**（自动填充）。

---

### 表名：`mcp_tool_impl_mapping`
- **用途**：将工具映射到具体实现（本地 Bean、远程 HTTP、gRPC 等）。
- **职责**：实现层路由与调用配置。
- **表类型**：关联表

#### 字段说明
- `id`：`bigserial` **主键**。
- `server_code`：`varchar(100)` – 所属服务器。
- `tool_name`：`varchar(200)` – 对应 `mcp_tool_catalog.tool_name`。
- `impl_type`：`varchar(64)` – 实现类型（LOCAL/REMOTE）。
- `execution_mode`：`varchar(32)` – 执行模式。
- `bean_name`、`method_name`：`varchar(255)` – 本地实现的 Spring Bean 与方法。
- `route_uri`：`varchar(255)` – 远程实现的 HTTP 路由。
- `timeout_ms`：`integer` – 超时时间（毫秒）。
- `retry_policy`：`jsonb` – 重试策略。
- `enabled`：`boolean` – 是否启用。
- `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**（自动填充）。

---

### 表名：`mcp_server_registry`
- **用途**：MCP 服务器注册表，包含服务地址、健康状态、鉴权配置等。
- **职责**：服务发现与健康监控。
- **表类型**：注册表（配置）

#### 字段说明
- `id`：`bigserial` **主键**。
- `server_code`：`varchar(100)` – 服务器唯一编码。
- `server_name`：`varchar(255)` – 可读名称。
- `description`、`base_url`、`transport_type`、`auth_type`：`text`/`varchar` – 基础信息。
- `auth_config`：`jsonb` – 鉴权配置（如 token、证书）。
- `enabled`：`boolean` – 是否启用。
- `health_status`：`varchar(32)` – 健康状态（UP/DOWN）。
- `last_sync_at`：`timestamp` – 最近同步时间。
- `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**（自动填充）。

---

### 表名：`mcp_resource_catalog`
- **用途**：统一管理各种资源（文件、模型、数据集），提供统一的元数据查询。
- **职责**：资源索引、版本控制、向量化检索。
- **表类型**：资源目录表

#### 字段说明
- `id`：`bigserial` **主键**。
- `server_code`：`varchar(100)` – 所属服务器。
- `resource_uri`：`varchar(255)` – 资源唯一标识（文件路径、URL 等）。
- `name`、`description`、`mime_type`：`varchar`/`text` – 基础描述。
- `annotations`、`tags`：`jsonb` – 额外标签与注解。
- `raw_payload`：`jsonb` – 原始内容（如二进制元数据）。
- `enabled`：`boolean` – 是否启用。
- `embedding`：`vector(768)` – 向量化表示（用于向量检索）。
- `synced_at`、`created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**。

---

### 表名：`mcp_prompt_catalog`
- **用途**：Prompt（提示词）统一管理，支持版本化、向量检索与标签化。
- **职责**：为 LLM 调用提供可审计的 Prompt 存储。
- **表类型**：配置表

#### 字段说明
- `id`：`bigserial` **主键**。
- `server_code`：`varchar(100)` – 所属服务器。
- `prompt_name`：`varchar(255)` – Prompt 名称。
- `title`、`description`：`varchar`/`text` – 可读信息。
- `arguments_schema`：`jsonb` – 参数 JSON Schema。
- `raw_payload`：`jsonb` – 原始 Prompt 内容。
- `tags`：`jsonb` – 标签集合。
- `enabled`：`boolean` – 是否启用。
- `version`：`varchar(32)` – 版本号。
- `embedding`：`vector(768)` – 向量化表示（用于语义检索）。
- `synced_at`、`created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**。

---

### 表名：`mcp_skills`（已废弃）
- **用途**：历史 Skill 表，已在迁移阶段转入 `mcp_prompt_catalog` 与 `mcp_tool_catalog`。
- **备注**：保留仅供兼容，业务已不再直接使用。
- **表类型**：历史表（已退役）

---

### 表名：`tasks`
- **用途**：统一的异步任务表，涵盖后台执行、审批流以及与 MCP 交互的任务。
- **职责**：任务调度、状态追踪、审计。
- **表类型**：任务调度表（无对应实体类）

#### 字段说明
- `task_id`：`bigserial` **主键**（雪花 ID）。
- `resource_id`：`bigint` – 关联资源（如文档、模型）。
- `status`：`varchar(20)` **NOT NULL** – 状态（PENDING、RUNNING、COMPLETED、REJECTED、FAILED、PENDING_APPROVAL）。
- `server_code`：`varchar(100)` – 所属 MCP 服务器。
- `tool_name`：`varchar(200)` – 执行的工具或工作流名称。
- `approval_id`：`varchar(100)` – 关联审批任务 ID。
- `session_id`：`varchar(100)` – 会话 ID（关联 `agent_session`）。
- `input_args`、`approval_payload`、`result`：`text` – 参数、审批上下文及执行结果（JSON）。
- `error_code`：`varchar(100)` – 结构化错误码。
- `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**（自动填充）。

---

## 📦 3️⃣ 日志与审计

### 表名：`luna_log`
- **用途**：平台统一日志表，记录业务操作、异常、审计等信息。
- **职责**：统一日志查询、审计追踪、费用统计。
- **表类型**：日志表

#### 字段说明
- `id`：`bigserial` **主键**。
- `log_type`：`varchar(50)` **NOT NULL** – 日志类别（INFO、ERROR、AUDIT 等）。
- `module`：`varchar(100)` – 所属模块（如 `plan`、`memory`）。
- `action`：`varchar(100)` – 业务动作标识。
- `content`：`text` – 日志正文摘要。
- `request_data`、`response_data`：`jsonb` – 请求/响应数据（可选）。
- `error_message`、`error_stack`：`text` – 错误信息与堆栈（仅错误日志）。
- `cost_time`：`bigint` – 耗时（毫秒）。
- `operator_id`：`varchar(64)` – 操作人标识（用户 ID 或系统 ID）。
- `trace_id`：`varchar(128)` – 链路追踪 ID。
- `create_at`：`timestamp` **默认 CURRENT_TIMESTAMP**。

---

## 📦 4️⃣ 调度 / 提醒

### 表名：`schedule_task`
- **用途**：日程与待办事项，用于 Luna 主动提醒或执行任务（如定时触发、闹钟）。
- **职责**：调度系统调用、用户提醒。
- **表类型**：调度表

#### 字段说明
- `id`：`bigserial` **主键**。
- `content`：`text` – 任务内容描述。
- `trigger_time`：`timestamp` – 触发时间（若为提醒类任务）。
- `status`：`smallint` – 状态（0‑待处理,1‑已完成,2‑已取消,3‑已过期）。
- `task_type`：`smallint` – 任务类型（0‑REMINDER,1‑ACTION,2‑TODO）。
- `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**（自动填充）。
- `deleted`：`integer` **默认 0** – 逻辑删除标记（对应实体 `@TableLogic`）。

---

## 📦 5️⃣ 用户偏好

### 表名：`user_preference`
- **用途**：存储用户自定义的键值对偏好（UI 配置、快捷键、个性化设置），支持向量化检索用于相似用户推荐。
- **职责**：用户级别的可扩展配置。
- **表类型**：配置表

#### 字段说明
- `id`：`bigserial` **主键**。
- `pref_key`：`varchar(255)` – 偏好键。
- `pref_value`：`varchar(255)` – 偏好值（可为 JSON 字符串）。
- `description`：`text` – 描述说明。
- `embedding`：`vector(768)` – 向量化表示（用于相似度检索）。
- `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**（自动填充）。
- `deleted`：`integer` **默认 0** – 逻辑删除（`@TableLogic`）。

---

## 📦 6️⃣ 记忆（Memory V2）
> 记忆子系统使用 PostgreSQL 的 `vector` 扩展实现向量化检索，所有表均在 `memory_runtime_v2_schema.sql` 中定义。

### 表名：`principal`
- **用途**：统一的主体（用户、系统）身份表。
- **职责**：统一管理平台内所有主体（用户、系统、服务）的身份信息，提供唯一标识和元数据。
- **表类型**：身份表
- **字段说明**：
  - `principal_id` (PK)：主键，自增唯一标识主体。
  - `principal_type`：主体类型，默认 `USER`。
  - `tenant_id`：租户标识。
  - `display_name`：显示名称。
  - `profile_json`：JSON 格式的元数据。
  - `created_at`：记录创建时间。
  - `updated_at`：记录更新时间。

### 表名：`agent_identity`
- **用途**：Agent（AI 实例）身份信息。
- **职责**：维护 AI Agent 实例的身份、人格配置及默认语气，用于创建和管理各类 Agent。
- **表类型**：Agent 表
- **字段说明**：
  - `agent_id` (PK)：主键，自增唯一标识 Agent。
  - `agent_name`：Agent 名称。
  - `persona_name`：人格名称。
  - `persona_desc`：人格描述。
  - `default_tone`：默认语气。
  - `config_json`：配置 JSON。
  - `created_at`：创建时间。
  - `updated_at`：更新时间。

### 表名：`agent_session`
- **用途**：会话层面的状态管理（任务/关系双状态）。
- **职责**：记录会话上下文及任务/关系状态，支持多种会话模式（TASK、COMPANION、HYBRID），并关联当前计划和目标。
- **表类型**：会话状态表
- **字段说明**：
  - `session_id` (PK)：会话唯一标识。
  - `principal_id`：关联主体。
  - `agent_id`：关联 Agent。
  - `session_type`：会话类型，默认 `HYBRID`。
  - `task_state`：任务状态，例如 `IDLE`。
  - `relational_state`：关系状态，例如 `COLD_START`。
  - `current_plan_id`：当前计划 ID。
  - `current_goal`：当前目标文本。
  - `last_user_message_at`：用户最后消息时间。
  - `last_agent_message_at`：Agent 最后消息时间。
  - `metadata_json`：会话元数据。
  - `created_at`：创建时间。
  - `updated_at`：更新时间。
- **约束**：`ck_agent_session_type` 限制取值 `('TASK','COMPANION','HYBRID')`。

### 表名：`state_transition_log`
- **用途**：记录状态迁移日志（任务/关系状态），支持审计与回放。
- **职责**：审计任务或关系状态迁移，记录变更来源、触发类型及原因，支持回放与追踪。
- **表类型**：日志表
- **字段说明**：
  - `id` (PK)：日志主键。
  - `session_id`：关联会话。
  - `state_domain`：状态域，`TASK` 或 `RELATION`。
  - `from_state`：来源状态。
  - `to_state`：目标状态。
  - `trigger_type`：触发类型。
  - `trigger_ref`：触发引用。
  - `reason`：变更原因。
  - `payload_json`：附加负载。
  - `created_at`：创建时间。

### 表名：`conversation_message`
- **用途**：会话中的消息存档（包括多模态内容）。
- **职责**：存储对话期间的消息内容及元信息（角色、类型、追踪 ID），支持文本和多模态数据。
- **表类型**：消息存储表
- **字段说明**：
  - `message_id` (PK)：消息主键。
  - `session_id`：关联会话。
  - `plan_id`：关联计划。
  - `role`：角色 (`USER`/`ASSISTANT`)。
  - `message_type`：消息类型，默认 `TEXT`。
  - `content_text`：文本内容。
  - `content_json`：结构化内容。
  - `trace_id`：追踪 ID。
  - `created_at`：创建时间。

### 表名：`event_inbox`
- **用途**：事件队列（用户输入、工具结果、系统事件等），供异步处理。
- **职责**：异步事件队列，缓存用户输入、工具结果、审批请求等，供后台处理和回调。
- **表类型**：事件队列表
- **字段说明**：
  - `event_id` (PK)：事件主键。
  - `session_id`：关联会话。
  - `event_type`：事件类型（`USER_INPUT`、`TOOL_RESULT`、`APPROVAL`、`SYSTEM`、`TIMER`）。
  - `payload_json`：事件负载。
  - `status`：状态，默认 `PENDING`。
  - `trace_id`：追踪 ID。
  - `created_at`：创建时间。
  - `updated_at`：更新时间。

### 表名：`task_working_memory`
- **用途**：任务层面的 Working Memory，保存原始/精炼目标、约束、关键实体/事实、当前激活节点等。
- **职责**：保存任务执行过程中的 Working Memory，包括目标、约束、关键实体、事实、激活节点等，以支撑任务计划和决策。
- **表类型**：Working Memory 表
- **字段说明**：
  - `twm_id` (PK)：工作内存主键。
  - `session_id`：关联会话。
  - `principal_id`：关联主体。
  - `plan_id`：关联计划。
  - `goal_raw`：原始目标文本。
  - `goal_refined`：精炼目标。
  - `intent_json`：意图 JSON。
  - `constraints_json`：约束 JSON。
  - `success_criteria_json`：成功标准 JSON。
  - `assumptions_json`：假设 JSON。
  - `key_entities_json`：关键实体 JSON。
  - `key_facts_json`：关键事实 JSON。
  - `unresolved_questions_json`：未解答问题 JSON。
  - `risks_json`：风险 JSON。
  - `active_phase_id`：当前激活阶段 ID。
  - `active_node_id`：当前激活节点 ID。
  - `recent_tool_outputs_json`：最近工具输出 JSON。
  - `local_scratchpad`：本地临时存储。
  - `version`（默认 1）：版本号。
- **索引**：唯一索引 `uk_task_working_memory_session` 确保同会话唯一。

### 表名：`task_working_memory_slot`
- **用途**：Working Memory 中的键值槽，用于保存分层、优先级、来源等属性。
- **职责**：在 Working Memory 中保存键值对槽位，记录层级、优先级及来源信息。
- **表类型**：键值槽表
- **字段说明**：
  - `id` (PK)：槽位主键。
  - `twm_id`（外键）：所属 Working Memory ID。
  - `slot_name`：槽位名称。
  - `slot_type`：槽位类型。
  - `slot_value_json`：槽位值 JSON。
  - `priority`（默认 50）：优先级。
  - `freshness_score`（默认 1.0）：新鲜度分数。
- **索引**：唯一索引 `uk_task_working_memory_slot`（`twm_id, slot_name`）。

### 表名：`task_semantic_fact`
- **用途**：语义事实存储，支持向量检索、置信度、时效性。
- **职责**：存储结构化语义事实，提供向量检索、置信度与时效性，以支持推理和决策。
- **表类型**：事实表
- **字段说明**：
  - `fact_id` (PK)：事实主键。
  - `principal_id`：关联主体。
  - `scope_type`：范围类型（`USER`、`SESSION`、`PLAN`、`GLOBAL`）。
  - `fact_type`：事实类型（`PREFERENCE`、`PROFILE`、`RULE`、`CONSTRAINT`、`DOMAIN_FACT`）。
  - `fact_key`：事实键。
  - `fact_value_text`：文本值。
  - `fact_value_json`：JSON 值。
  - `description`：描述。
  - `confidence_score`：置信度分数。
  - `stability_score`：稳定性分数。
  - `source_type`：来源类型。
  - `source_ref`：来源引用。
  - `valid_from`：有效起始时间。
  - `valid_to`：有效结束时间。
  - `last_confirmed_at`：最近确认时间。
  - `embedding`（向量）：向量嵌入。
  - `deleted`（逻辑删除）：删除标记。
  - `created_at`：创建时间。
  - `updated_at`：更新时间。
- **索引**：`idx_task_semantic_fact_embedding` 使用 `ivfflat`（向量相似度检索）。

### 表名：`knowledge_document`
- **用途**：知识库文档元数据（标题、来源、所有者等）。
- **职责**：记录知识库文档的元数据（标题、来源、所有者等），为文档切分和检索提供索引入口。
- **表类型**：文档元数据表
- **字段说明**：
  - `doc_id` (PK)：文档主键。
  - `owner_scope`：拥有者范围。
  - `owner_ref`：拥有者引用。
  - `source_type`：来源类型。
  - `source_uri`：来源 URI。
  - `title`：文档标题。
  - `metadata_json`：元数据 JSON。
  - `created_at`：创建时间。

### 表名：`knowledge_chunk`
- **用途**：文档切分后的块，存储文本、摘要、关键词、向量、全文检索字段（TSVector）。
- **职责**：保存文档切分块的文本、摘要、关键词、向量及全文检索信息，支撑向量相似度搜索。
- **表类型**：文档块表
- **字段说明**：
  - `chunk_id` (PK)：块主键。
  - `doc_id`（FK → `knowledge_document.doc_id`）：所属文档 ID。
  - `chunk_order`：块顺序。
  - `chunk_text`：块文本。
  - `chunk_summary`：块摘要。
  - `keywords_json`：关键词 JSON。
  - `embedding`（向量）：向量嵌入。
  - `tsv`（全文检索向量）：全文检索向量。
  - `metadata_json`：元数据 JSON。
  - `created_at`：创建时间。
- **索引**：向量索引 `idx_knowledge_chunk_embedding`、全文索引 `idx_knowledge_chunk_tsv`（GIN）。

### 表名：`task_episode`
- **用途**：任务执行过程的剧本（成功、失败、决策等），用于事后复盘与学习。
- **职责**：记录任务执行过程的剧本（Episode），包括成功/失败/决策等信息，用于事后复盘与学习。
- **表类型**：剧本表
- **字段说明**：
  - `episode_id` (PK)：剧本主键。
  - `principal_id`：关联主体。
  - `session_id`：关联会话。
  - `plan_id`：关联计划。
  - `episode_type`：剧本类型（`SUCCESS`、`FAILURE`、`DECISION`、`PARTIAL`）。
  - `title`：标题。
  - `task_goal`：任务目标。
  - `context_json`：上下文 JSON。
  - `trajectory_summary`：轨迹摘要。
  - `outcome_summary`：结果摘要。
  - `outcome_status`：结果状态。
  - `lessons_learned`：经验教训。
  - `importance_score`：重要性分数。
  - `reusability_score`：可复用性分数。
  - `embedding`：向量嵌入。
  - `created_at`：创建时间。
- **索引**：向量索引 `idx_task_episode_embedding`（IVFFLAT）。

### 表名：`task_episode_step`
- **用途**：Episode 中的具体步骤记录（如决策点、子任务）。
- **职责**：记录剧本中每一步的细节（步骤类型、内容、负载），支持细粒度审计。
- **表类型**：步骤表
- **字段说明**：
  - `id` (PK)：步骤主键。
  - `episode_id`：所属剧本 ID。
  - `step_order`：步骤顺序。
  - `step_type`：步骤类型。
  - `title`：标题。
  - `content_text`：内容文本。
  - `payload_json`：负载 JSON。
  - `created_at`：创建时间。

### 表名：`task_procedure_pattern`
- **用途**：任务编排模式（如 PLANNING_PATTERN、TOOL_CHAIN、RECOVERY、VALIDATION），用于自动生成或匹配流程。
- **职责**：定义任务编排模式模板，存储触发条件、适用范围及步骤顺序，供自动生成或匹配流程使用。
- **表类型**：模式表
- **字段说明**：
  - `procedure_id` (PK)：模式主键。
  - `procedure_type`：模式类型（受限）。
  - `name`：模式名称。
  - `description`：描述。
  - `trigger_conditions_json`：触发条件 JSON。
  - `applicability_scope_json`：适用范围 JSON。
  - `pattern_steps_json`：模式步骤 JSON。
  - `success_signals_json`：成功信号 JSON。
  - `failure_signals_json`：失败信号 JSON。
  - `source_kind`：来源类别。
  - `confidence_score`：置信度分数。
  - `usage_count`：使用次数。
  - `success_count`：成功次数。
  - `fail_count`：失败次数。
  - `embedding`：向量嵌入。
  - `created_at`：创建时间。
  - `updated_at`：更新时间。
- **索引**：向量索引 `idx_task_procedure_embedding`（IVFFLAT）。

### 表名：`task_reflection_record`
- **用途**：任务/节点的事后反思记录（根因、改进建议）。
- **职责**：记录任务或节点的事后反思、根因分析与改进建议，为持续改进提供依据。
- **表类型**：反思记录表
- **字段说明**：
  - `reflection_id` (PK)：反思记录主键。
  - `plan_id`：关联计划。
  - `node_id`：关联节点。
  - `reflection_type`：反思类型。
  - `trigger_reason`：触发原因。
  - `observation`：观察结果。
  - `root_cause`：根因。
  - `proposed_fix`：建议修复。
  - `extracted_pattern_json`：提取的模式 JSON。
  - `quality_score`：质量分数。
  - `created_at`：创建时间。

### 表名：`relational_working_memory`
- **用途**：关系层面的 Working Memory（情感、交互目标、情绪等），支持对话式情感建模。
- **职责**：维护关系层面的 Working Memory，记录情感、交互目标、情绪等信息，支持情感建模与关系管理。
- **表类型**：关系 Working Memory 表
- **字段说明**：
  - `rwm_id` (PK)：关系 Working Memory 主键。
  - `session_id`：关联会话。
  - `principal_id`：关联主体。
  - `current_relational_state`：当前关系状态。
  - `inferred_emotion`：推断情绪。
  - `emotion_confidence`：情绪置信度。
  - `desired_tone`：期望语气。
  - `support_intent`：支持意图。
  - `interaction_goal`：交互目标。
  - `caution_flags_json`：警示标志 JSON。
  - `recent_bond_signals_json`：近期情感信号 JSON。
  - `recent_sensitive_signals_json`：近期敏感信号 JSON。
  - `updated_at`：更新时间。

### 表名：`relational_profile`
- **用途**：用户关系画像，记录亲密度、偏好、边界等信息。
- **职责**：保存用户关系画像，包括亲密度、偏好、边界等，以支持个性化交互和安全治理。
- **表类型**：关系画像表
- **字段说明**：
  - `profile_id` (PK)：画像主键。
  - `principal_id`：关联主体。
  - `relationship_stage`：关系阶段。
  - `preferred_name`：首选名称。
  - `preferred_tone`：首选语气。
  - `emotional_support_style`：情感支持风格。
  - `humor_preference`：幽默偏好。
  - `intimacy_preference`：亲密偏好。
  - `interaction_style_json`：交互风格 JSON。
  - `boundary_preferences_json`：边界偏好 JSON。
  - `sensitive_topics_json`：敏感话题 JSON。
  - `comfort_triggers_json`：舒适触发因素 JSON。
  - `no_go_patterns_json`：禁用模式 JSON。
  - `trust_score`：信任分数。
  - `intimacy_score`：亲密度分数。
  - `created_at`：创建时间。
  - `updated_at`：更新时间。
- **唯一索引**：`uk_relational_profile_principal` 确保每个主体唯一画像。

## 📑 附录：完整表结构与约束说明

> 为了便于快速查阅，各表的完整结构、关键约束、索引以及对应的 SQL 定义文件已在下面列出。点击链接可查看原始 `CREATE TABLE` 语句（行号对应文件首行）。

### OpenClaw 编排（任务流）

- [`plan_instance`](src/main/resources/sql/luna/public/plan_instance.sql:1)
  - 主键：`plan_id`
  - 外键：`session_id → agent_session.session_id`
  - 索引：`idx_plan_instance_session_id`、`idx_plan_instance_status`、`idx_plan_instance_created_at`、`idx_plan_instance_updated_at`、`idx_plan_instance_status_updated_at`
  - 备注：记录一次完整的计划实例，`status` 使用枚举（0‑PENDING、1‑RUNNING、2‑WAITING_USER_APPROVAL、3‑SUCCESS、4‑FAILED、5‑CANCELLED），`final_status` 区分成功/失败/部分成功/取消。

- [`plan_phase`](src/main/resources/sql/luna/public/plan_phase.sql:1)
  - 主键：`phase_id`
  - 外键：`plan_id → plan_instance.plan_id`
  - 唯一约束：`uk_plan_phase_order (plan_id, phase_order)`
  - 索引：`idx_plan_phase_plan_id`、`idx_plan_phase_status`、`idx_plan_phase_plan_status`
  - `status` 枚举：0‑PENDING、1‑RUNNING、2‑SUCCESS、3‑FAILED。

- [`plan_node`](src/main/resources/sql/luna/public/plan_node.sql:1)
  - 主键：`node_id`
  - 外键：`plan_id → plan_instance.plan_id`、`phase_id → plan_phase.phase_id`
  - 索引：`idx_plan_node_plan_id`、`idx_plan_node_phase_id`、`idx_plan_node_status`、`idx_plan_node_plan_status`、`idx_plan_node_phase_status`、`idx_plan_node_parallel_group`、`idx_plan_node_server_code`、`idx_plan_node_capability_type`、`idx_plan_node_approval_status`、`idx_plan_node_risk_level`、`idx_plan_node_model_hint`、`idx_plan_node_created_at`
  - GIN 索引：`dependencies`、`resource_hint`、`resolved_input_json`、`output_for_next`
  - `node_type` 枚举：0‑ANALYZE、1‑TOOL、3‑VALIDATE、5‑REPORT、6‑CODE、7‑PROMPT、8‑RESOURCE、9‑WORKFLOW。

- [`plan_edge`](src/main/resources/sql/luna/public/plan_edge.sql:1)
  - 主键：`id`
  - 外键：`plan_id → plan_instance.plan_id`、`from_node_id → plan_node.node_id`、`to_node_id → plan_node.node_id`
  - 唯一约束：`uk_plan_edge_unique (plan_id, from_node_id, to_node_id)`
  - 索引：`idx_plan_edge_plan_id`、`idx_plan_edge_from_node`、`idx_plan_edge_to_node`

- [`plan_blueprint`](src/main/resources/sql/luna/public/plan_blueprint.sql:1)
  - 主键：`id`
  - 外键：`plan_id → plan_instance.plan_id`
  - 唯一约束：`uk_plan_blueprint_plan_version (plan_id, plan_version)`
  - 索引：`idx_plan_blueprint_plan_id`、`idx_plan_blueprint_generated_at`、`idx_plan_blueprint_json_gin`

- [`plan_checkpoint`](src/main/resources/sql/luna/public/plan_checkpoint.sql:1)
  - 主键：`checkpoint_id`
  - 外键：`plan_id → plan_instance.plan_id`
  - 索引：`暂无（业务自行创建）`

- [`plan_report`](src/main/resources/sql/luna/public/plan_report.sql:1)
  - 主键：`report_id`
  - 外键：`plan_id → plan_instance.plan_id`、`session_id → agent_session.session_id`
  - 索引：`暂无（业务自行创建）`

- [`plan_event_log`](src/main/resources/sql/luna/public/plan_event_log.sql:1)
  - 主键：`event_id`
  - 外键：`plan_id → plan_instance.plan_id`、`phase_id → plan_phase.phase_id`（可空）、`node_id → plan_node.node_id`（可空）
  - 索引：`暂无（业务自行创建）`

### CodeOps / MCP（工具/能力管理）

- [`mcp_tool_catalog`](src/main/resources/sql/luna/public/mcp_tool_catalog.sql:1)
  - 主键：`id`
  - 唯一业务键：`server_code + tool_name`
  - 索引：`暂无（业务自行创建）`

- [`mcp_tool_impl_mapping`](src/main/resources/sql/luna/public/mcp_tool_impl_mapping.sql:1)
  - 主键：`id`
  - 外键：`tool_name → mcp_tool_catalog.tool_name`

- [`mcp_server_registry`](src/main/resources/sql/luna/public/mcp_server_registry.sql:1)
  - 主键：`id`
  - 唯一业务键：`server_code`

- [`mcp_resource_catalog`](src/main/resources/sql/luna/public/mcp_resource_catalog.sql:1)
  - 主键：`id`
  - 索引：`暂无（业务自行创建）`

- [`mcp_prompt_catalog`](src/main/resources/sql/luna/public/mcp_prompt_catalog.sql:1)
  - 主键：`id`
  - 唯一业务键：`server_code + prompt_name`

### 调度 / 提醒

- [`schedule_task`](src/main/resources/sql/luna/public/schedule_task.sql:1)
  - 主键：`id`
  - `status` 枚举：0‑待处理、1‑已完成、2‑已取消、3‑已过期

### 用户偏好

- [`user_preference`](src/main/resources/sql/luna/public/user_preference.sql:1)
  - 主键：`id`

### 记忆（Memory V2）

- 详细结构请参考 `memory_runtime_v2_schema.sql`，其中包含主体、会话、状态日志、消息、事件队列、Working Memory、语义事实、知识文档与 Chunk、任务 Episode 与 Step、任务模式等。所有表均使用向量 (`vector(768)`) 存储用于相似度检索的向量字段，并配有对应的 GIN/IVFFLAT 索引。

---

如需进一步细化可根据实际业务需求增补约束或索引。

## 🖊️ 其他关键表的详细字段说明

### 调度 / 提醒

#### `schedule_task`
- **用途**：日程与待办事项，用于 Luna 主动提醒或执行任务（如定时触发、闹钟）。
- **职责**：调度系统调用、用户提醒。
- **表类型**：调度表
- **字段说明**：
  - `id`：`bigserial` **主键**，唯一标识一条任务记录。
  - `content`：`text` – 任务内容描述，供前端展示。
  - `trigger_time`：`timestamp` – 计划触发时间点。若为 `REMINDER` 类任务，则在此时间触发提醒。
  - `status`：`smallint` – 状态枚举：`0` 待处理、`1` 已完成、`2` 已取消、`3` 已过期。
  - `task_type`：`smallint` – 任务类型枚举：`0` REMINDER（提醒），`1` ACTION（后台执行），`2` TODO（用户待办）。
  - `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP** – 自动记录创建与最近更新时间。
  - `deleted`：`integer` **默认 0** – 逻辑删除标记（对应实体 `@TableLogic`）。

### 用户偏好

#### `user_preference`
- **用途**：存储用户自定义的键值对偏好（UI 配置、快捷键、个性化设置），支持向量化检索用于相似用户推荐。
- **职责**：用户级别的可扩展配置。
- **表类型**：配置表
- **字段说明**：
  - `id`：`bigserial` **主键**。
  - `pref_key`：`varchar(255)` – 偏好键，例如 `theme`, `shortcut.save`。
  - `pref_value`：`varchar(255)` – 偏好值，可为 JSON 字符串以存储结构化配置。
  - `description`：`text` – 对该偏好的说明，帮助前端编辑。
  - `embedding`：`vector(768)` – 向量化表示，用于相似度检索。
  - `created_at`、`updated_at`：`timestamp` **默认 CURRENT_TIMESTAMP**。
  - `deleted`：`integer` **默认 0** – 逻辑删除标记。

### CodeOps / MCP（工具/能力管理）

#### `mcp_tool_catalog`
- **用途**：中心化的工具/能力元数据登记表，统一管理工具名称、描述、输入/输出 schema、标签等信息。
- **职责**：提供统一的注册、查询、权限控制入口。
- **表类型**：核心配置表
- **关键字段**：
  - `id`：`bigserial` **主键**。
  - `server_code`：`varchar(100)` – 所属 MCP 服务器标识，用于多租户/多机房区分。
  - `tool_name`：`varchar(200)` – 工具唯一标识（业务层唯一）。
  - `title`、`description`：供 UI 展示的可读信息。
  - `input_schema`、`output_schema`：`jsonb` – JSON Schema 定义调用时的参数校验与返回结果校验。
  - `annotations`、`tags`：`jsonb` – 额外标签与注解，支持分类（如 `search`, `codegen`）和治理（如 `sensitivity`）。
  - `enabled`：`boolean` – 是否启用，禁用后调度器不再调用。
  - `version`：`varchar(32)` – 版本号，配合实现映射实现多版本管理。
  - `execution_mode`：`varchar(32)` – `SYNC`/`ASYNC`，影响调用方式与超时策略。
  - `requires_approval`：`boolean` – 是否需要人工审批。
  - `sensitivity`：`varchar(32)` – 敏感度等级（`LOW`, `MEDIUM`, `HIGH`），用于安全审计。
  - `raw_payload`：`jsonb` – 原始配置 JSON，便于回滚。
  - `embedding`：`vector(768)` – 向量化用于语义检索。

#### `mcp_tool_impl_mapping`
- **用途**：将工具映射到具体实现（本地 Bean、远程 HTTP、gRPC 等），实现层路由与调用配置。
- **职责**：管理实现细节、超时、重试策略等。
- **表类型**：关联表
- **关键字段**：
  - `id`：`bigserial` **主键**。
  - `server_code`：`varchar(100)` – 所属服务器。
  - `tool_name`：`varchar(200)` – 对应 `mcp_tool_catalog.tool_name`。
  - `impl_type`：`varchar(64)` – 实现类型：`LOCAL`（Spring Bean）或 `REMOTE`（HTTP/gRPC）。
  - `execution_mode`：`varchar(32)` – 覆盖默认执行模式。
  - `bean_name`、`method_name`：`varchar(255)` – 本地实现的 Spring Bean 与方法名称。
  - `route_uri`：`varchar(255)` – 远程实现的 HTTP 路径。
  - `timeout_ms`：`integer` – 调用超时时间（毫秒），防止阻塞。
  - `retry_policy`：`jsonb` – 重试策略（最大次数、退避算法）。
  - `enabled`：`boolean` – 是否启用此实现。

#### `mcp_server_registry`
- **用途**：MCP 服务器注册表，记录服务地址、健康状态、鉴权配置等信息。
- **职责**：服务发现、健康监控、鉴权统一。
- **表类型**：注册表（配置）
- **关键字段**：
  - `id`：`bigserial` **主键**。
  - `server_code`：`varchar(100)` – 唯一服务器编号。
  - `server_name`：`varchar(255)` – 业务可读名称。
  - `description`、`base_url`、`transport_type`、`auth_type`：`text`/`varchar` – 基础服务信息。
  - `auth_config`：`jsonb` – 鉴权配置（token、证书路径等）。
  - `enabled`：`boolean` – 是否可用。
  - `health_status`：`varchar(32)` – 健康状态（`UP` / `DOWN`）。
  - `last_sync_at`：`timestamp` – 最近一次同步时间。

#### `mcp_resource_catalog`
- **用途**：统一管理各种资源（文件、模型、数据集），提供统一的元数据查询。
- **职责**：资源索引、版本控制、向量化检索。
- **表类型**：资源目录表
- **关键字段**：
  - `id`：`bigserial` **主键**。
  - `server_code`：`varchar(100)` – 所属服务器。
  - `resource_uri`：`varchar(255)` – 资源唯一标识（文件路径、URL、模型 ID）。
  - `name`、`description`、`mime_type`：`varchar`/`text` – 基础描述信息。
  - `annotations`、`tags`：`jsonb` – 额外标签与注解，支持分类检索。
  - `raw_payload`：`jsonb` – 二进制元数据或额外配置信息。
  - `enabled`：`boolean` – 是否启用。
  - `embedding`：`vector(768)` – 向量化用于语义检索。

#### `mcp_prompt_catalog`
- **用途**：Prompt（提示词）统一管理，支持版本化、向量检索与标签化。
- **职责**：为 LLM 调用提供可审计的 Prompt 存储。
- **表类型**：配置表
- **关键字段**：
  - `id`：`bigserial` **主键**。
  - `server_code`：`varchar(100)` – 所属服务器。
  - `prompt_name`：`varchar(255)` – Prompt 名称，业务唯一标识。
  - `title`、`description`：`varchar`/`text` – 可读信息。
  - `arguments_schema`：`jsonb` – 参数 JSON Schema。
  - `raw_payload`：`jsonb` – 原始 Prompt 内容（模板字符串）。
  - `tags`：`jsonb` – 标签集合（如 `search`、`summarize`）。
  - `enabled`：`boolean` – 是否启用。
  - `version`：`varchar(32)` – 版本号。
  - `embedding`：`vector(768)` – 向量化用于语义检索。

