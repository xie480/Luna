# OpenClaw Skill-Tool 对照与缺口分析（含现有四个 Skill）

## 1. 文档目的

本文件用于回答以下问题：

1. `openclaw_desktop_orchestration_design.md` 中规划的 Skill，各自应依赖哪些 Tool？
2. 这些 Skill 是否已具备“可立即创建并执行”的条件？
3. 还缺哪些 Tool 或关键能力？
4. 除了设计文档中的 Skill，也覆盖当前已创建的四个 Skill。

---

## 2. 评估口径

### 2.1 状态定义

- **可立即创建并执行**：所需核心 Tool 已存在且可用（非占位），能跑通主路径。
- **可创建但执行能力不完整**：Tool 名义上存在，但关键步骤是占位实现或缺关键保障。
- **暂不可创建（缺关键 Tool）**：核心 Tool 缺失，Skill 即使创建也无法实际执行。

### 2.2 Tool 状态定义

- **已实现**：后端已有对应方法，且非占位。
- **占位实现**：有方法和 JSON，但返回 `NOT_IMPLEMENTED` 或空壳结果。
- **环境未启用**：代码已接入，但依赖外部系统环境，当前默认未启用。
- **缺失**：方法/JSON/可执行链路不存在。

---

## 3. 已有 Tool 清单（用于后续判定）

## 3.1 编排与报告类
- save_plan_blueprint（已实现）
- load_plan_blueprint（已实现）
- list_phase_nodes（已实现）
- update_node_status（已实现）
- append_node_output（已实现）
- query_plan_progress（已实现）
- record_plan_audit_log（已实现）
- checkpoint_plan_state（已实现）
- emit_plan_event_sse（已实现）
- write_html_report_file（已实现）
- open_browser_with_file（已实现）

## 3.2 调度锁类
- acquire_execution_lock（已实现，Redis分布式锁）
- release_execution_lock（已实现，Lua校验释放）

## 3.3 CodeOps 类
- read_repo_tree（已实现）
- read_source_file（已实现）
- write_source_file（已实现）
- apply_unified_patch（已实现，基于 git apply）
- run_build_command（已实现）
- run_test_command（已实现）
- run_lint_command（已实现）
- run_format_command（已实现）
- collect_test_report（已实现，JUnit/Surefire XML）
- git_create_checkpoint（已实现）
- git_rollback_checkpoint（已实现）
- search_symbol_references（已实现，文件扫描基础版）
- scan_dependency_vulnerabilities（环境未启用，依赖SCA工具）

## 3.4 桌面增强类
- capture_desktop_screenshot（环境未启用，依赖桌面环境）
- detect_ui_elements（环境未启用，依赖OCR/UIA能力）

## 3.5 通用业务 Tool（已在系统长期使用）
- web_search / image_search / news_search / lens_search / web_scrape（已实现）
- manage_memory（已实现）
- manage_user_preference（已实现）
- manage_schedule_task（已实现）
- manage_knowledge_base（已实现）
- manage_log（已实现）

---

## 4. 设计文档中的 Skill 逐项分析

## 4.1 plan_user_requirement_bigmodel
### 建议依赖 Tool
- save_plan_blueprint
- record_plan_audit_log
- emit_plan_event_sse

### 判定
**可立即创建并执行（基础版）**

### 说明
- 该 Skill 关键是模型规划与结果落盘；落盘和事件工具已具备。
- 风险点不在 Tool 缺失，而在“规划输出结构质量与校验严格度”。

---

## 4.2 validate_plan_blueprint
### 建议依赖 Tool
- load_plan_blueprint
- record_plan_audit_log
- emit_plan_event_sse
- （可选）append_node_output（记录校验结果摘要）

### 判定
**可立即创建并执行（规则校验版）**

### 说明
- 数据读取与日志工具齐全。
- 若要“自动修复蓝图”，仍需结合模型与 patch 机制（非硬依赖）。

---

## 4.3 execute_plan_phase
### 建议依赖 Tool
- list_phase_nodes
- update_node_status
- append_node_output
- query_plan_progress
- emit_plan_event_sse
- record_plan_audit_log
- acquire_execution_lock
- release_execution_lock

### 判定
**可立即创建并执行（基础版）**

### 说明
- 关键锁能力已落地。
- 高并发与锁续租（watchdog）可作为后续增强项。

---

## 4.4 replan_failed_nodes
### 建议依赖 Tool
- list_phase_nodes
- query_plan_progress
- append_node_output
- record_plan_audit_log
- emit_plan_event_sse
- （可选）save_plan_blueprint（写新版本）

### 判定
**可创建但执行能力不完整**

### 主要缺口
- 局部重规划本身依赖“模型 + 图结构补丁策略”，Tool 侧基础够，但缺稳定“图补丁应用流程”。

---

## 4.5 aggregate_phase_outputs
### 建议依赖 Tool
- list_phase_nodes
- append_node_output
- record_plan_audit_log

### 判定
**可立即创建并执行**

---

## 4.6 generate_task_report_and_open
### 建议依赖 Tool
- write_html_report_file
- open_browser_with_file
- record_plan_audit_log
- emit_plan_event_sse

### 判定
**可立即创建并执行**

### 说明
- 报告落盘和打开浏览器已落地。
- 建议补一个“打开失败兜底通知”模板（非阻塞）。

---

## 4.7 publish_plan_sse_events
### 建议依赖 Tool
- emit_plan_event_sse
- record_plan_audit_log（建议）

### 判定
**可立即创建并执行**

---

## 4.8 checkpoint_plan_state
### 建议依赖 Tool
- checkpoint_plan_state
- record_plan_audit_log

### 判定
**可立即创建并执行**

---

## 4.9 plan_code_change_tasks
### 建议依赖 Tool
- read_repo_tree
- read_source_file
- search_symbol_references
- record_plan_audit_log
- emit_plan_event_sse

### 判定
**可立即创建并执行（基础版）**

### 说明
- `search_symbol_references` 已可用（基础扫描版），后续可升级 LSP 精确版本。

---

## 4.10 generate_code_patch
### 建议依赖 Tool
- read_source_file
- write_source_file（直写模式）
- apply_unified_patch（补丁模式）
- git_create_checkpoint
- record_plan_audit_log

### 判定
**可立即创建并执行（基础版）**

---

## 4.11 generate_test_cases
### 建议依赖 Tool
- read_repo_tree
- read_source_file
- write_source_file
- run_format_command（可选）
- record_plan_audit_log

### 判定
**可立即创建并执行（基础版）**

---

## 4.12 analyze_test_failure_and_fix
### 建议依赖 Tool
- run_test_command
- collect_test_report
- read_source_file
- apply_unified_patch / write_source_file
- git_rollback_checkpoint
- record_plan_audit_log
- emit_plan_event_sse

### 判定
**可立即创建并执行（基础版）**

---

## 4.13 summarize_code_changes_for_report
### 建议依赖 Tool
- query_plan_progress
- list_phase_nodes
- record_plan_audit_log
- write_html_report_file（可选直写）

### 判定
**可立即创建并执行**

---

## 5. 当前已创建四个 Skill 分析

以下四个 Skill 来自你仓库现有 JSON：

1. `intel_research_pipeline`
2. `skill_search_ingest_kb`
3. `skill_system_audit_diagnosis`
4. `skill_user_profile_build`

> 说明：你之前日志显示 `intel_research_pipeline` 出现过 `toolSlots` 缺失报错，该问题属于 Skill 配置完整性问题，而非 Tool 目录缺失。

---

## 5.1 intel_research_pipeline
### 建议依赖 Tool
- web_search
- web_scrape
- news_search（可选）
- manage_knowledge_base（可选入库）
- manage_log（审计）

### 判定
**可立即创建并执行（前提：toolSlots 配置正确）**

### 风险提示
- 若缺 `toolSlots`，会直接失败（你已在线上遇到）。

---

## 5.2 skill_search_ingest_kb
### 建议依赖 Tool
- web_search
- web_scrape
- manage_knowledge_base
- manage_log（可选）

### 判定
**可立即创建并执行**

---

## 5.3 skill_system_audit_diagnosis
### 建议依赖 Tool
- manage_log
- query_plan_progress（若纳入编排视角）
- record_plan_audit_log（若纳入 OpenClaw 事件）

### 判定
**可立即创建并执行**

---

## 5.4 skill_user_profile_build
### 建议依赖 Tool
- manage_user_preference
- manage_memory
- manage_schedule_task（可选，若含行为计划）
- manage_log（可选）

### 判定
**可立即创建并执行**

---

## 6. 总结矩阵（Skill -> 当前可用性）

| Skill | 结论 |
|---|---|
| plan_user_requirement_bigmodel | 可立即创建并执行 |
| validate_plan_blueprint | 可立即创建并执行 |
| execute_plan_phase | 可立即创建并执行（基础版） |
| replan_failed_nodes | 可创建但执行能力不完整 |
| aggregate_phase_outputs | 可立即创建并执行 |
| generate_task_report_and_open | 可立即创建并执行 |
| publish_plan_sse_events | 可立即创建并执行 |
| checkpoint_plan_state | 可立即创建并执行 |
| plan_code_change_tasks | 可立即创建并执行（基础版） |
| generate_code_patch | 可立即创建并执行（基础版） |
| generate_test_cases | 可立即创建并执行（基础版） |
| analyze_test_failure_and_fix | 可立即创建并执行（基础版） |
| summarize_code_changes_for_report | 可立即创建并执行 |
| intel_research_pipeline | 可立即创建并执行（需修正 toolSlots） |
| skill_search_ingest_kb | 可立即创建并执行 |
| skill_system_audit_diagnosis | 可立即创建并执行 |
| skill_user_profile_build | 可立即创建并执行 |

---

## 7. 关键剩余缺口 Tool 清单（优先级）

## P0（环境依赖项）
1. scan_dependency_vulnerabilities（需接入SCA工具）
2. capture_desktop_screenshot（需桌面环境）
3. detect_ui_elements（需OCR/UIA能力）

---

## 8. 建议下一步

1. 按 `docs/openclaw_tool_dependency_guide.md` 完成依赖安装后，启用环境依赖 Tool。  
2. 在 Skill 注册阶段增加“Tool 可用性标签”（已实现 / 环境未启用）。  
3. 对基础版实现（patch、symbol 引用）补充更高精度版本（LSP、三方补丁库）。  

```