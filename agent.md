# Luna Agent 项目总览与执行规范（agent.md）

## 1. 文档目的

本文件用于统一团队与 AI Agent 在本仓库内的协作方式，明确：

1. 项目架构全阶段蓝图（基于 README.md）
2. 当前项目进度与阶段判断（基于现有文件清单与命名推断）
3. 后续阶段路线图与落地优先级
4. 当前项目工程规范
5. 代码实现与变更规则（人类开发者与 AI 代码助手共用）

---

## 2. 项目定位（来自 README）

Luna 是一个本地化 Desktop AI Agent，目标是“陪伴 + 主动性 + 多模态 + 可执行工具链（MCP）+ 长期稳定运行”。

核心能力面向：

- 人格稳定（System Prompt 宪章约束）
- 对话与情绪表达（后续结合 Live2D / TTS / ASR）
- 多层记忆（短期上下文、中期结构化、长期持久化）
- RAG 知识库（检索、写入、增量更新）
- MCP 工具调用（可审计、可控、最小权限）
- 生命周期管理（开机预热、关机总结）
- 工程化保障（日志审计、备份恢复、监控告警）

---

## 3. 当前代码结构与类职责（基于已提供文件清单）

> 说明：以下为“按包结构与命名约定”的职责归纳，属于架构级解读。  
> 若需“每个类逐行准确分析”，请补充对应源码全文到对话。

### 3.1 配置层（config / properties）

- `config/LunaAgentConfig`：Spring 配置装配入口，负责 Agent 相关 Bean 的初始化与依赖注入。
- `config/SwaggerStartupPrinter`：启动后输出接口文档访问信息（可观测性辅助）。
- `properties/*Property`（Auth/Embedding/Ollama/Qwen）：外部配置映射，承载模型、认证、向量等参数。

### 3.2 常量层（constants）

- `DateTimeConstant`：统一时间格式规范。
- `LogActionConstant` / `LogModuleConstant`：日志审计分类标准。
- `LunaStateConstant`：系统状态机标识（WORKING / MEMORY / SEARCHING...）。
- `ModelHintConstant`：模型输入输出标签与 hint 约束。
- `RedisKeyConstant`：Redis key 命名规范，避免硬编码散落。
- `RocketMqConstant`：MQ topic/group 统一配置。
- `SymbolConstant`：上下文摘要等系统标识位。

### 3.3 领域实体与请求响应（entity / entity.query / sse / llm / mq.dto）

- `ChatRequest` / `ChatMessage` / `LoginRequest`：API 入参模型。
- `ApprovalTask`、`McpSkill`、`Resource`、`ToolCallingContext`：工具执行、资源描述、调用上下文等核心领域对象。
- `*PageQueryRequest` + `PagedResponse<T>`：分页查询统一模型。
- `LlmResponse`：模型输出封装。
- `LunaStatusMessage`：SSE 状态推送消息结构。
- `mq/dto/*Message`：异步消息体（日志、知识库写入、技能执行、摘要任务）。

### 3.4 数据访问层（mapper）

- `KnowledgeBaseMapper` / `MemoryMapper` / `UserPreferenceMapper` / `McpSkillMapper` / `McpToolMapper`：
  - 提供基础 CRUD（继承 MyBatis-Plus `BaseMapper`）
  - 提供向量检索 `searchByVector(...)`（看起来是 pgvector 距离检索）

### 3.5 服务抽象层（service / adapter / gate）

- `adapter/LlmAdapter`：模型调用抽象（统一 `generate(prompt)`）。
- `service/BlueprintValidationService`：蓝图校验入口，保障策略/结构合法性。
- `service/LunaLogService` / `MemoryService`：日志与记忆服务接口。
- `gate/ExecutionGate`：执行门控（频率、权限、状态、策略闸门）核心组件候选。

### 3.6 工具与基础设施（tools / utils / python）

- `tools/BaseTool`：工具返回结果统一封装（success/fail JSON）基类。
- `utils/JacksonObjectMapper`：统一 JSON 序列化与时间反序列化规则。
- `utils/ServiceCommunicateUtil`：进程内符号通信（状态位）辅助。
- `utils/SnowflakeIdUtil`：ID 生成器。
- `utils/ToolCallingContextHolder`：ThreadLocal 工具调用上下文。
- Python：
  - `embedding.py`：向量化服务能力
  - `rerank.py`：重排能力
  - `luna_inference_grpc_server.py`：推理 gRPC 服务
  - `rerank_service_http.py`：重排 HTTP 服务封装

### 3.7 提示词系统

- `prompt/PromptTemplates`：系统级 Prompt 宪章与模板中心（人格稳定核心）

---

## 4. 当前项目进度判断（结合 README 阶段）

README 定义了阶段 1 ~ 23。结合当前文件清单，**推断**如下：

### 已有明显落地基础（大概率已完成或部分完成）

- 阶段1：准备和环境配置（Spring + 配置类完备）
- 阶段2：本地对话（LlmAdapter、Chat 实体、推理服务存在）
- 阶段3：会话短期记忆（RedisKeyConstant、上下文相关常量存在）
- 阶段4：滚动摘要和上下文管理（SummaryMessage、CONTEXT_SUMMARY_FLAG 存在）
- 阶段5：本地向量知识库（Embedding、Mapper 向量检索、KB 消息体存在）
- 阶段6：多层记忆体系（Memory、UserPreference、KnowledgeBase 相关结构存在）
- 阶段7：基础 MCP（McpSkill/McpTool/ToolCallingContext/BaseTool 存在）
- 阶段8：联网搜索能力（LogAction 含 search_web/news/images/lens）
- 阶段9：提示词实时管理（PromptTemplates 存在，但“实时更新+版本回滚”未见明确实现证据）
- 阶段12：日志记录和审计（LunaLogService + LogMessage + action/module 常量存在）

### 部分信号存在、但未能确认完整闭环

- 阶段9.5 生命周期管理（有 SummaryMessage，但缺少开关机流程代码证据）
- 阶段10 主动更新 KB/提示词（消息结构有，但策略/审批闭环待确认）
- 阶段11 运行时主动 MCP（ExecutionGate 存在，但主动触发链路待确认）
- 阶段13 定时备份与恢复（未见明确备份模块文件）
- 阶段14 监控和告警（未见监控配置/告警集成文件）
- 阶段15 人格一致性量化评估（有 Prompt 宪章，缺评估流水线证据）

### 大概率尚未完成（或不在当前后端仓库内）

- 阶段16~23（桌面嵌入、磁盘扫描、桌面事件感知、语音、Live2D、最终部署验收）
  - 这些更偏客户端/系统集成层，目前清单以后端服务为主。

---

## 5. 后续阶段建议（按优先级）

1. **先补齐工程稳定性链路（13/14/15）**
   - 备份恢复、监控告警、人格一致性评估自动化
2. **再强化自主行为治理（9.5/10/11）**
   - 主动写入策略、审批与审计、频率控制、用户反馈回路
3. **最后推进桌面与多模态（16~23）**
   - 桌面事件采集、语音链路、Live2D 映射、安装部署与 72h 稳定性验收

---

## 6. 当前项目规范（统一约束）

## 6.1 架构规范

- 分层清晰：controller -> service -> mapper，禁止跨层直连污染。
- 常量集中：禁止魔法字符串散落，统一进入 `constants`。
- 配置外置：所有环境差异项必须走 `properties` + 配置文件。
- 能力解耦：模型、向量、重排、MCP 走抽象接口，不绑死单实现。
- 异步解耦：耗时/可重试任务优先走 MQ。

## 6.2 数据与接口规范

- 请求/响应对象与实体分离（DTO/DO/POJO 职责明确）。
- 分页统一使用 `PagedResponse<T>` 语义。
- 时间格式统一遵循 `DateTimeConstant`。
- 统一 ObjectMapper（`JacksonObjectMapper`），避免多套 JSON 行为。

## 6.3 日志与审计规范

- 所有关键动作必须记录 module + action（参照 Log 常量）。
- 工具调用、知识写入、搜索、异常必须可追溯 requestId / traceId。
- 敏感信息脱敏后再入日志。

## 6.4 并发与上下文规范

- ThreadLocal（`ToolCallingContextHolder`）使用后必须 clear，防止线程复用污染。
- 共享状态（如 SymbolMap）变更需考虑并发可见性与原子性。

## 6.5 安全规范

- 工具调用默认拒绝，按白名单放行（最小权限）。
- 外部输入必须校验（BlueprintValidation + 参数校验）。
- Prompt/策略更新必须留版本与审计记录。

---

## 7. 编码规则（必须遵循）

1. **先设计后编码**：新功能先补充接口、领域模型、错误码与日志点位。
2. **禁止硬编码**：
   - key、topic、状态、动作名一律走常量
   - 可配置参数一律走 `*Property`
3. **单一职责**：
   - Controller 不写业务编排
   - Service 不直接拼 SQL（交给 Mapper）
4. **可测试性优先**：
   - 核心策略类（门控、记忆写入、触发策略）必须可单测
5. **可观测性优先**：
   - 每个异步任务记录开始/结束/耗时/结果
6. **错误处理统一**：
   - 明确可重试与不可重试异常
   - 外部依赖失败要有降级路径（fallback）
7. **向量检索规范**：
   - 统一 topK、相似度阈值、召回与重排参数来源
8. **MCP 执行规范**：
   - 先鉴权 -> 再门控 -> 后执行 -> 最后审计
9. **提交规范**：
   - 一次提交只做一类变更（功能/重构/文档分开）
10. **文档同步**：
   - 任何影响阶段状态或架构边界的改动，必须同步 README/agent.md

---

## 8. AI Agent 协作规则（本仓库）

- 若用户仅提供“文件摘要”，Agent 不得假设完整实现细节。
- 任何修改必须基于“已提供到聊天中的文件全文”。
- 若需编辑未提供全文的文件，先请求用户补充文件内容。
- 输出代码变更时，必须提供完整文件内容，不得省略。

---

## 9. 当前阶段结论（本次评估）

基于现有材料，项目大致位于：

- **主线能力约在阶段 8 ~ 12 区间**
- 即：对话、记忆、RAG、基础 MCP、部分审计能力已具备雏形
- 下一里程碑建议锁定在：**13（备份恢复）+14（监控告警）+11（主动 MCP 治理闭环）**

> 待你补充更多核心实现类全文后，可输出“逐类已完工/未完工清单 + 精确阶段百分比 + 迭代计划（按周）”。

---

## 10. 下一步建议（可执行）

建议你下一条消息提供以下文件全文（优先级从高到低）：

1. 核心流程：Chat/Agent 主服务实现类（若存在 `*ServiceImpl`、`*Controller`）
2. 记忆与摘要写入链路实现
3. MCP 执行编排实现（含 ExecutionGate 调用点）
4. 日志落库与 MQ 消费者实现
5. Prompt 动态管理实现（若有版本管理）

拿到后我可以生成：
- 《逐类职责表（精确版）》
- 《阶段完成度打分表（1~23）》
- 《未来 3 个迭代冲刺计划（含任务拆解）》
