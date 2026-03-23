# OpenClaw Tool 能力清单（独立文档）

## 1. 文档目的

本文件用于从《openclaw_desktop_orchestration_design.md》中抽离并细化 Tool 清单，形成可直接落地的执行层设计说明。  
重点描述每个 Tool 的：

- 功能定义
- 作用场景
- 输入参数
- 输出结构
- 实现思路
- 安全与审批建议
- 失败处理与重试策略

> 说明：本文档聚焦 **Tool（原子执行能力）**，不覆盖 Skill（编排能力）。  
> 说明：**本文档中的 JSON 参考格式以 `json/tool/` 目录下现有文件为准**（如 `web_search.json`、`manage_memory.json`、`manage_log.json`）。

---

## 2. 设计原则

### 2.1 Tool 定位
Tool 是最小可执行单元，特征为：

- 原子性：一次只做一件事
- 可观测：入参、出参、耗时、错误可追踪
- 可复用：可被多个 Skill/PlanNode 调用
- 可审计：支持 traceId、operatorId、审批链路

### 2.2 统一返回规范（建议）
所有 Tool 建议统一返回 JSON：

```json
{
  "status": "success|error|pending_approval",
  "message": "简要说明",
  "data": {},
  "errorCode": "可选",
  "costMs": 123
}
```

### 2.3 参数校验规范（建议）
- 必填参数：明确 required
- 结构参数：使用 JSON Schema 校验
- 类型边界：长度、枚举、格式（路径/URL/ID）严格校验
- 安全参数：命令执行类必须白名单

### 2.4 安全分级（建议）
- LOW：普通读操作（查询、读取状态）
- MEDIUM：写操作（更新状态、写文件）
- HIGH：系统/代码高风险操作（命令执行、补丁应用、回滚）

---

## 3. Tool 总览分组

## 3.1 编排状态与存储类
1. save_plan_blueprint
2. load_plan_blueprint
3. list_phase_nodes
4. update_node_status
5. append_node_output
6. query_plan_progress
7. record_plan_audit_log
8. checkpoint_plan_state（若以 Tool 方式实现）

## 3.2 调度与执行控制类
9. acquire_execution_lock
10. release_execution_lock
11. emit_plan_event_sse

## 3.3 报告与展示类
12. write_html_report_file
13. open_browser_with_file

## 3.4 代码工程能力类（CodeOps）
14. read_repo_tree
15. read_source_file
16. write_source_file
17. apply_unified_patch
18. run_build_command
19. run_test_command
20. run_lint_command
21. run_format_command
22. collect_test_report
23. git_create_checkpoint
24. git_rollback_checkpoint
25. search_symbol_references
26. scan_dependency_vulnerabilities

## 3.5 桌面增强类（可选）
27. capture_desktop_screenshot
28. detect_ui_elements

---

## 4. Tool 详细清单

## 4.1 save_plan_blueprint

### 功能
保存全局规划蓝图（PlanBlueprint）到持久层（DB）与缓存层（Redis）。

### 作用
- 冻结 BigModel 一次性规划结果
- 支持版本化与回放
- 支持中断恢复

### 输入参数
- planId: string（必填）
- planVersion: number（必填）
- blueprintJson: object（必填）
- generatedByModel: string（可选）
- generatedAt: string（可选，ISO时间）

### 输出
- status
- data.planId
- data.planVersion
- data.savedToDb: boolean
- data.savedToRedis: boolean

### 实现思路
1. 先写 DB（`plan_blueprint`）
2. 再写 Redis（`luna:plan:{planId}:blueprint:{version}`）
3. 失败时记录审计日志并返回 error

### 风险与控制
- 幂等键：`(planId, planVersion)` 唯一约束
- 大 JSON 建议压缩存储（Redis 可选 gzip）

---

## 4.2 load_plan_blueprint

### 功能
加载指定 plan 的蓝图（优先 Redis，回源 DB）。

### 作用
- 阶段执行器读取最新有效规划
- 恢复执行时快速重建 DAG

### 输入参数
- planId: string（必填）
- planVersion: number（可选，不传则取最新）

### 输出
- data.blueprintJson
- data.planVersion
- data.source: redis|db

### 实现思路
1. 查 Redis
2. miss 则查 DB
3. 回填 Redis 缓存

---

## 4.3 list_phase_nodes

### 功能
列出指定阶段节点及其状态。

### 输入参数
- planId: string（必填）
- phaseId: string（必填）

### 输出
- data.nodes: array（节点列表）
- 每个节点包含：nodeId/status/retryCount/maxRetry/dependencies

### 实现思路
- DB 查询 `plan_node where plan_id=? and phase_id=?`
- 可追加实时态（Redis）覆盖

---

## 4.4 update_node_status

### 功能
更新节点状态与执行元数据。

### 输入参数
- planId: string
- nodeId: string
- status: enum(PENDING/RUNNING/SUCCESS/FAILED/BLOCKED/APPROVAL_PENDING/SKIPPED)
- costMs: number（可选）
- failReason: string（可选）
- retryCount: number（可选）

### 输出
- data.updated: boolean

### 实现思路
- 乐观更新：只允许合法状态迁移
- 写 DB + 写 Redis 快照
- 推送对应 SSE 事件（可联动 emit_plan_event_sse）

### 风险控制
- 禁止非法跳转（如 PENDING -> SUCCESS，未经过 RUNNING）
- 必须记录 updatedAt

---

## 4.5 append_node_output

### 功能
写入节点输出（outputJson）与传递字段（outputForNext）。

### 输入参数
- planId: string
- nodeId: string
- outputJson: object
- outputForNext: object（可选）
- trimPolicy: object（可选，避免过大）

### 输出
- data.saved: boolean
- data.outputSizeBytes

### 实现思路
- 持久化到 `plan_node.output_json/output_for_next`
- 输出过大时截断并保留摘要

---

## 4.6 acquire_execution_lock

### 功能
获取执行锁，防止并行冲突。

### 输入参数
- lockKey: string（如 app/window/file 维度）
- owner: string（planId:nodeId）
- ttlSec: number（默认60）

### 输出
- data.acquired: boolean
- data.lockKey
- data.expireAt

### 实现思路
- Redis `SET NX EX`
- 失败则由调度器稍后重试

### 风险控制
- 必须搭配 release_execution_lock
- 需要看门狗续约（长任务）

---

## 4.7 release_execution_lock

### 功能
释放执行锁。

### 输入参数
- lockKey: string
- owner: string

### 输出
- data.released: boolean

### 实现思路
- Lua 脚本比对 owner 后删除（防误删）

---

## 4.8 query_plan_progress

### 功能
查询计划整体进度（给前端补偿拉取）。

### 输入参数
- planId: string

### 输出
- data.planStatus
- data.phaseProgress
- data.nodeStats（success/failed/running/pending）
- data.lastEvents

### 实现思路
- 聚合 DB + Redis + 最近事件日志

---

## 4.9 emit_plan_event_sse

### 功能
发送计划事件到 SSE 通道。

### 输入参数
- clientId: string（默认 default）
- eventType: enum（如 PLAN_NODE_SUCCESS）
- payload: object

### 输出
- data.sent: boolean

### 实现思路
- 复用 `SseSessionManager.send(...)`
- 失败写日志，不中断主流程

---

## 4.10 record_plan_audit_log

### 功能
记录计划级审计日志。

### 输入参数
- planId
- phaseId（可选）
- nodeId（可选）
- level: INFO|WARN|ERROR
- eventType
- eventPayload
- traceId

### 输出
- data.eventId

### 实现思路
- 写入 `plan_event_log`
- 对 error 级别可触发告警

---

## 4.11 write_html_report_file

### 功能
将报告 HTML 写入文件系统。

### 输入参数
- planId: string
- htmlContent: string
- fileName: string（可选）
- outputDir: string（可选，默认 ./data/reports）

### 输出
- data.reportPath
- data.reportUrl(file://)

### 实现思路
- 创建目录
- 原子写文件（临时文件 rename）
- 返回绝对路径

### 风险控制
- 路径穿越防护（禁止 `..`）
- 文件名白名单字符

---

## 4.12 open_browser_with_file

### 功能
唤起系统默认浏览器打开本地报告。

### 输入参数
- reportPath: string（必填）

### 输出
- data.openResult: SUCCESS|FAILED
- data.error（可选）

### 实现思路
- Java Desktop API 或 OS 命令适配
- 失败不影响任务主状态，但需记录

---

## 4.13 read_repo_tree

### 功能
读取仓库目录树（可限制深度）。

### 输入参数
- repoPath: string
- maxDepth: number（默认4）
- includeHidden: boolean（默认false）

### 输出
- data.tree（目录结构）
- data.fileCount

### 实现思路
- NIO 遍历
- 忽略大目录（如 .git、node_modules、target）

---

## 4.14 read_source_file

### 功能
读取源码文件内容。

### 输入参数
- filePath: string
- encoding: string（默认 UTF-8）
- maxBytes: number（默认1MB）

### 输出
- data.content
- data.size

### 风险控制
- 限制可读根目录（repoPath 内）
- 二进制文件拒绝读取

---

## 4.15 write_source_file

### 功能
写入源码文件（支持备份）。

### 输入参数
- filePath
- content
- backup: boolean（默认true）

### 输出
- data.written
- data.backupPath（可选）

### 风险控制
- 高风险文件（配置、密钥）可触发审批
- 保留备份便于回滚

---

## 4.16 apply_unified_patch

### 功能
应用 unified diff patch。

### 输入参数
- repoPath
- patchText
- checkOnly: boolean（默认false）

### 输出
- data.appliedFiles
- data.failedFiles
- data.checkOnlyResult

### 实现思路
- 先 dry-run
- 再正式 apply
- 冲突时返回明细

### 安全建议
- sensitivity 建议 HIGH
- 默认需审批

---

## 4.17 run_build_command

### 功能
执行构建命令（mvn/gradle 等）。

### 输入参数
- workDir
- command（白名单）
- timeoutSec（默认600）

### 输出
- data.exitCode
- data.stdoutTail
- data.stderrTail
- data.costMs

### 风险控制
- 严格命令白名单
- 禁止 shell 拼接注入

---

## 4.18 run_test_command

### 功能
执行测试命令并收集结果。

### 输入参数
- workDir
- command（白名单）
- timeoutSec

### 输出
- data.exitCode
- data.testSummary（若可解析）
- data.reportPaths

### 实现思路
- 执行后调用 collect_test_report 结构化结果

---

## 4.19 run_lint_command

### 功能
执行静态检查。

### 输入参数
- workDir
- command
- timeoutSec

### 输出
- data.issuesCount
- data.issuesSample
- data.exitCode

---

## 4.20 run_format_command

### 功能
执行格式化工具。

### 输入参数
- workDir
- command
- timeoutSec

### 输出
- data.changedFiles
- data.exitCode

---

## 4.21 collect_test_report

### 功能
聚合 surefire/junit 等测试报告为标准 JSON。

### 输入参数
- reportDirs: array
- parserType: junit|surefire|auto

### 输出
- data.passed
- data.failed
- data.skipped
- data.total
- data.failedCases

### 实现思路
- XML/JSON 报告解析器统一封装

---

## 4.22 git_create_checkpoint

### 功能
创建本地检查点（commit/stash/tag）。

### 输入参数
- repoPath
- mode: commit|stash|tag
- message

### 输出
- data.checkpointId（commit hash 或 stash id）

### 风险控制
- 仅允许仓库内执行
- 记录操作者与 traceId

---

## 4.23 git_rollback_checkpoint

### 功能
回滚到指定检查点。

### 输入参数
- repoPath
- checkpointId
- mode: hard|soft|mixed（默认 soft）

### 输出
- data.rolledBack: boolean
- data.currentHead

### 安全建议
- sensitivity HIGH
- 必须审批

---

## 4.24 search_symbol_references

### 功能
按符号查找引用。

### 输入参数
- repoPath
- symbol
- language（可选）

### 输出
- data.references（文件、行号、片段）

### 实现思路
- 优先 LSP/ctags，降级 grep

---

## 4.25 scan_dependency_vulnerabilities

### 功能
扫描依赖漏洞（SCA）。

### 输入参数
- repoPath
- ecosystem: maven|npm|pip|auto
- failOnSeverity: LOW|MEDIUM|HIGH（可选）

### 输出
- data.vulnCount
- data.bySeverity
- data.itemsSample

---

## 4.26 capture_desktop_screenshot

### 功能
抓取当前桌面截图用于状态判断。

### 输入参数
- monitorIndex（可选）
- region（可选，x/y/w/h）

### 输出
- data.imagePath
- data.width
- data.height

### 安全建议
- 涉及隐私，建议审批或显式用户授权

---

## 4.27 detect_ui_elements

### 功能
检测 UI 元素（OCR/控件树）。

### 输入参数
- imagePath（或 screenshot=true）
- detectorType: ocr|uiautomation|hybrid
- targetHints（可选）

### 输出
- data.elements（文本、坐标、置信度）

### 实现思路
- OCR + UIA 融合
- 提供统一坐标系

---

## 5. Tool JSON 参考格式（以 json/tool 目录为准）

以下模板与现有文件风格一致（如 `json/tool/web_search.json`、`json/tool/manage_memory.json`）：

```json
{
  "name": "tool_name",
  "description": "工具描述（说明调用时机与用途）",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "xxxTools",
  "methodName": "methodName",
  "inputSchema": {
    "type": "object",
    "properties": {},
    "required": ["action"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "status": {
        "type": "string",
        "description": "执行状态，常见值：success 或 error。"
      },
      "data": {
        "type": "object",
        "description": "返回数据。"
      }
    }
  }
}
```

### 5.1 字段说明（对齐现有工具 JSON）
- `name`: 工具唯一名（与数据库 `mcp_tools.name` 对齐）
- `description`: 工具用途、触发时机、行为边界
- `version`: 工具版本（建议 semver）
- `owner`: 归属（当前项目多为 `System`）
- `beanName`: Spring Bean 名称（如 `searchTools` / `memoryTools`）
- `methodName`: 反射执行方法名（如 `web_search` / `manageMemory`）
- `inputSchema`: 参数 JSON Schema（用于校验）
- `outputSchema`: 输出结构 JSON Schema（用于约束前端解析）

### 5.2 命名规范建议
- 管理型工具：`manage_xxx`（如 `manage_memory`）
- 检索型工具：`xxx_search`（如 `web_search`）
- 动作型工具：动词开头（如 `open_browser_with_file`）
- 参数字段：驼峰命名（与现有 JSON 一致，如 `triggerTime`、`sourceType`）

---

## 6. 与现有系统对接建议

## 6.1 与 ReflectionToolExecutor 对接
- 参数通过 `@RequestParam` 或 JSON->POJO 绑定
- 所有 Tool 返回字符串 JSON（与当前实现一致）

## 6.2 与审批系统对接
- HIGH 风险 Tool：`requiresApproval=true` 或 `sensitivity=HIGH`
- 经 `ExecutionGate -> ApprovalService` 中断与恢复

## 6.3 与状态推送对接
- Tool 执行前后通过 `LunaStateAspect` 推送状态
- 关键节点通过 `emit_plan_event_sse` 推送业务事件

## 6.4 与日志审计对接
- 统一使用 `@LunaLogRecord`
- 失败路径必须有 errorCode 与 traceId

---

## 7. 分阶段落地建议

## Phase A（最小闭环）
- save/load blueprint
- list/update node
- append output
- query progress
- write/open report
- emit SSE
- record audit log

## Phase B（调度增强）
- execution lock
- checkpoint
- 更细粒度状态机与重试

## Phase C（CodeOps）
- read/write file
- apply patch
- run build/test/lint/format
- collect reports
- git checkpoint/rollback

## Phase D（桌面增强）
- screenshot
- UI element detection

---

## 8. 验收标准（Tool 维度）

- 每个 Tool 有可执行 inputSchema/outputSchema
- 关键 Tool（写操作）有幂等与审计
- HIGH 风险 Tool 审批链路可用
- 失败返回结构统一（status/errorCode/message）
- 全部 Tool 可在流程图中追踪耗时与结果
- 报告生成与打开工具在成功/失败任务中均可触发

---

## 9. 附录：建议 errorCode 命名

- TOOL_PARAM_INVALID
- TOOL_PERMISSION_DENIED
- TOOL_NEED_APPROVAL
- TOOL_EXEC_TIMEOUT
- TOOL_EXEC_FAILED
- TOOL_IO_ERROR
- TOOL_PATCH_CONFLICT
- TOOL_CMD_NOT_ALLOWED
- TOOL_REPORT_WRITE_FAILED
- TOOL_BROWSER_OPEN_FAILED

