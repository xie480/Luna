# Luna Agent 项目总览与执行规范（agent.md）

## 1. 文档目的

本文件用于统一团队与 AI Agent 在本仓库内的协作方式，明确：

1. 基于 `docs/` 全量文档重新定义 Luna 的定位与边界  
2. 当前架构分层、职责与系统能力地图  
3. 项目阶段路线（重排后）与当前进度判断  
4. 后续阶段落地优先级与里程碑  
5. 项目工程规范、协作规范、编码与变更规则  

---

## 2. 重新定义 Luna（基于 docs 全量文档）

## 2.1 产品定义（新版）

Luna 不是单纯聊天机器人，而是一个**可审计、可恢复、可编排、可审批、可演进**的本地化 AI Agent 平台：

- 以“陪伴式人格 + 长期记忆 + 主动行为”为体验核心
- 以“Plan/Phase/Node 编排 + Tool/Skill 数据驱动执行”为能力核心
- 以“安全门控 + HITL 审批 + 全链路审计 + 报告固化”为治理核心
- 以“多模型路由 + RAG + CodeOps + 桌面感知”为扩展核心

可概括为：  
**Luna = 人格化交互层 + 任务编排中枢 + MCP 执行系统 + 安全审批系统 + 可观测审计系统 + CodeOps 工程闭环 + 多模态扩展底座。**

---

## 2.2 技术定位（新版）

从 docs 可见，Luna 已从“对话系统”升级到“任务级自治系统”，关键特征：

1. **数据驱动 MCP（Tool/Skill Schema 化）**
   - Tool/Skill 元数据可注册、可检索、可版本化
   - 运行时按能力匹配动态选择资源，而非硬编码绑定

2. **OpenClaw 风格编排**
   - BigModel 一次性全局规划（PlanBlueprint）
   - 按 Phase 执行 DAG 节点，支持失败重试、局部重规划、状态恢复

3. **HITL 审批中断恢复**
   - 敏感动作不阻塞主请求
   - Redis 挂起任务 + SSE 推送审批 + 回调恢复执行链路

4. **全链路可观测**
   - `PLAN_*`、`SKILL_ASYNC_RESULT`、`luna-status` 统一事件化
   - SSE + 日志/事件落库双轨，支持实时看板与历史回放

5. **工程闭环能力（CodeOps）**
   - 读写代码、补丁应用、构建测试、失败修复、回滚、报告产出
   - 面向“自动化研发任务链”的可控落地

6. **推理服务常驻化方向**
   - Embedding/Rerank 从子进程转 HTTP 常驻，降低 P95/P99 抖动

---

## 3. 系统能力地图（按 docs 汇总）

## 3.1 交互层能力

- Chat 主链路（鉴权、上下文、RAG、工具调用、回复生成）
- SSE 实时状态与事件推送
- 前端审批弹窗、Plan 可视化、报告入口联动

## 3.2 认知与记忆层能力

- 短期记忆（会话上下文）
- 滚动摘要（异步压缩）
- 中长期记忆（Memory / Preference / KB）
- 检索增强（向量召回 + rerank）

## 3.3 执行层能力（MCP）

- Tool 原子能力执行（反射执行）
- Skill 复合能力编排（同步/异步）
- 资源路由（向量 + schema + 规则）
- ExecutionGate 安全门控

## 3.4 编排层能力（OpenClaw）

- Plan 创建、阶段执行、节点状态机
- 串并行调度、重试、失败修复、局部 replanning
- 任务报告生成并展示
- 图谱快照 + 增量事件驱动前端可视化

## 3.5 安全与治理能力

- JWT + jti 会话标识
- 敏感度分级（LOW/MEDIUM/HIGH）
- 审批中断恢复（HITL）
- 审计日志与事件追踪（traceId / planId）

## 3.6 工程与运维能力

- 命令白名单与危险符号拦截
- 检查点/回滚机制
- 异步消息解耦（日志/摘要/知识写入）
- 常驻推理服务化与回退策略

## 3.7 CodeOps 能力域

- 代码结构读取与符号引用检索
- 补丁生成与应用（含 dry-run/冲突处理）
- build/test/lint/format 执行与结果采集
- 测试失败归因与自动修复循环
- 变更汇总进入任务报告

---

## 4. 项目阶段全景（重排版，含 OpenClaw 与 CodeOps）

为避免旧编号 1~23 过于线性且无法体现编排与工程闭环，现按能力域重排为 **阶段 A~G（共 30 个子阶段）**。

## A. 对话与记忆基础（A1~A6）

- A1：准备和环境配置  
- A2：本地对话  
- A3：会话短期记忆  
- A4：滚动摘要和上下文管理  
- A5：本地向量知识库  
- A6：多层记忆体系  

## B. MCP 与主动能力（B1~B6）

- B1：基础 MCP  
- B2：联网搜索能力  
- B3：提示词实时管理  
- B4：生命周期管理（原 9.5）  
- B5：主动更新知识库与提示词  
- B6：运行时主动 MCP  

## C. 审计与稳定性治理（C1~C4）

- C1：日志记录和审计  
- C2：定时备份与恢复  
- C3：监控和告警  
- C4：人格稳定与一致性评估  

## D. OpenClaw 编排核心（D1~D5）

- D1：BigModel 一次性全局规划（PlanBlueprint Freeze）  
- D2：按阶段执行（Phase Executor）  
- D3：DAG 串并行调度与状态机  
- D4：局部重规划与失败恢复（replan_failed_nodes）  
- D5：Plan 可视化（快照 + SSE 增量 + 事件落库）  

## E. CodeOps 工程闭环（E1~E5）

- E1：代码任务规划（plan_code_change_tasks）  
- E2：补丁生成与应用（generate_code_patch / apply_unified_patch）  
- E3：测试与质量门禁（generate_test_cases / run_test_command / lint / format）  
- E4：失败归因与自动修复循环（analyze_test_failure_and_fix）  
- E5：变更总结与报告集成（summarize_code_changes_for_report）  

## F. 桌面与多模态（F1~F8）

- F1：桌面嵌入  
- F2：磁盘文件扫描与索引  
- F3：桌面状态感知  
- F4：基于桌面事件的主动输出  
- F5：语音输入  
- F6：Live2D 集成  
- F7：AI 语音（TTS/ASR）  
- F8：桌面部署与最终验收（72h 稳定性）  

## G. 推理服务与性能工程（G1~G2）

- G1：Embedding/Rerank 常驻化（HTTP）  
- G2：全链路性能优化（RAG、Router、SSE、DB、降级策略）  

---

## 5. 当前阶段判断（按 docs 证据）

### 已形成较强基础（大概率已落地）
- A1~A6：对话、记忆、RAG、多层记忆
- B1~B2：MCP 与搜索基础
- C1：日志审计基础
- D1~D3：OpenClaw MVP（可规划、可阶段执行、可事件推送）
- D5（部分）：已有 plan 可视化改造文档与接口路线
- G1（推进中）：Embedding/Rerank HTTP 常驻方案明确

### 进行中（有设计与部分实现信号）
- B3~B6：Prompt 动态、生命周期、主动行为治理
- C2~C4：备份、监控、人格一致性量化
- D4：局部重规划产品化
- E1~E5：CodeOps 已具备基础 Tool，向完整闭环演进
- G2：全链路性能治理持续迭代

### 相对靠后
- F1~F8：桌面与多模态完整落地

**结论**：项目主线处于  
**“A/B/C1 基础成型 + D（OpenClaw）进入 MVP 可运行阶段 + E（CodeOps）从可用走向闭环 + 向 C2/C3/C4 与 F 阶段推进”**。

---

## 6. 未来阶段路线（建议执行优先级）

## P0（近期必须）
1. D5：统一 Plan 事件协议并完成前端状态机对齐  
2. D4：replan_failed_nodes 子图补丁机制落地  
3. E3/E4：测试失败自动修复闭环跑通  
4. G1/G2：常驻推理稳定化 + 指标化监控  
5. D4/D5-记忆防漂移专项：执行记忆账本（Execution Memory Ledger）落地（防止多阶段任务遗忘原始用户命令）

## P1（下一里程碑）
1. C2：备份恢复演练  
2. C3：监控告警上线（审批耗时、回退率、节点失败率）  
3. C4：人格一致性自动评估流水线  
4. E5：CodeOps 变更总结标准化并接入报告模板  

## P2（中期）
1. F1~F4：桌面能力逐步接入（截图/UI/事件）  
2. F5~F7：语音 + Live2D 联动  
3. F8：72h 稳定性验收与安装部署闭环  

---

## 7. 当前项目规范（统一约束）

## 7.1 架构规范

- 分层明确：Controller -> Service -> Mapper / Executor
- 编排与执行解耦：Skill 负责编排，Tool 负责原子动作
- 安全与执行解耦：ExecutionGate/Approval 不侵入业务细节
- 事件驱动优先：状态变化必须可推送、可落库、可回放

## 7.2 数据与契约规范

- Tool/Skill 必须具备 `inputSchema` / `outputSchema`
- API 与 SSE payload 字段稳定优先，新增字段向后兼容
- 时间格式统一；枚举值统一；错误码统一

## 7.3 可观测性规范

- 关键链路必须有 `traceId`、`planId`、`phaseId`、`nodeId`
- 节点事件至少包含：status / costMs / retryCount / failReason
- 审批链路必须可追踪创建、推送、回调、恢复全过程

## 7.4 安全规范

- 高风险动作默认需审批（最小权限）
- 命令执行类能力必须白名单 + 注入拦截
- 敏感日志脱敏；本地路径与系统信息输出受控

## 7.5 一致性规范

- 文档、接口、事件协议、前端类型定义保持同步迭代
- README、agent.md、docs 内规范冲突时，以“最新落地协议”优先并及时回写文档

---

## 8. 编码与改动规则（强制）

1. **先定义契约再写实现**
   - 先定 input/output schema、错误码、事件载荷，再编码

2. **禁止魔法字符串**
   - key/topic/eventType/status/errorCode 必须集中管理

3. **状态机合法迁移**
   - Node/Phase 状态流转必须受限，禁止非法跳转

4. **失败优先设计**
   - 每个外部依赖都要有 timeout、retry、fallback、审计记录

5. **可恢复优先**
   - 中断点必须可重建（Redis/DB 快照、checkpoint）

6. **最小侵入改造**
   - 优先扩展，不破坏既有接口行为

7. **前后端协同**
   - 新增 SSE 事件或字段，必须同时提供前端接入说明

8. **测试要求**
   - 至少覆盖：成功路径、失败路径、超时路径、审批拒绝路径

9. **文档同步**
   - 改接口、改事件、改状态机必须同步 docs 与本文件

10. **提交规范**
   - 一次提交只解决一个主题（功能/重构/文档分离）

---

## 9. AI Agent 协作规则（仓库内）

- 仅依据“已加入聊天且声明为最新”的文件内容进行修改
- 对未提供全文文件，不做猜测式改动
- 输出变更必须给出完整文件内容，不省略
- 如需改动只读文件，先请求用户将其加入聊天

---

## 10. 当前阶段结论（本次更新）

Luna 当前已完成从“聊天+工具调用”到“任务编排+审批治理”的关键跃迁，并进入 **OpenClaw + CodeOps 双主线推进期**。  
下一阶段重点：

1. 把 OpenClaw 做到“可视化、可回放、可局部重规划”  
2. 把 CodeOps 做到“可回滚、可测试、可修复、可报告”  
3. 把治理层（备份/监控/一致性评估）补齐生产能力  
4. 把桌面多模态能力按风险分级逐步接入  
5. 把多任务编排记忆做到“不可漂移、可追溯、可恢复”，确保不遗忘用户原始命令

一句话：  
**Luna 正处于从 MVP 能跑，迈向生产级自治 Agent 平台的关键收敛期。**

---

## 11. 下一步执行建议（可直接开工）

1. 锁定统一事件协议 v1（PLAN_* / APPROVAL_REQUEST / SKILL_ASYNC_RESULT）  
2. 建立 Plan 可视化前后端联调基线（快照 + 增量 + 校准）  
3. 完成审批流压测脚本与过期策略演练  
4. 推理常驻服务接入统一健康检查与指标采集  
5. 输出 D/E/C 阶段迭代排期（按周拆任务）  
6. 启动“执行记忆账本（Execution Memory Ledger）”专项并完成最小可用实现（见 `docs/execution-memory-ledger.md`）

---

## 12. 执行记忆账本专项（Execution Memory Ledger）

### 12.1 背景与目标

在多阶段、多节点、可重规划的任务编排中，模型可能出现“目标漂移”与“上下文遗忘”。  
本专项目标：

1. 固化并保护用户原始命令（不可变）  
2. 固化 BigModel 生成并批准后的蓝图（PlanBlueprint）  
3. 全量记录 Phase/Node/Skill 执行事实（成功/失败/重试/产出）  
4. 提炼“可供下一阶段使用”的结构化关键结果  
5. 支撑中断恢复、局部重规划、可视化回放与审计追踪

### 12.2 双层记忆模型

1. **事件事实层（Event Ledger）**：append-only，不覆盖，保证真实性  
2. **工作快照层（Working Snapshot）**：阶段级摘要，保证模型可用性与低 token 成本

### 12.3 最小必备记录项（MVP）

- `immutableOriginalCommand`：用户原始命令（不可变）
- `approvedBlueprint`：当前计划蓝图（版本化）
- `currentPhase` / `completedPhases`
- `executedSkills`（含输入/输出摘要）
- `success` / `errorCode` / `failReason`
- `phaseKeyOutputs`（下一阶段关键输入）

### 12.4 事件建议（与现有 PLAN_* 兼容扩展）

- `PLAN_CREATED`
- `BLUEPRINT_FROZEN`
- `PHASE_STARTED`
- `SKILL_CALLED`
- `SKILL_RESULT`
- `PHASE_COMPLETED`
- `PHASE_FAILED`
- `REPLAN_TRIGGERED`
- `REPLAN_APPLIED`
- `PLAN_COMPLETED`
- `PLAN_FAILED`

### 12.5 落地要求（强制）

1. 每个 phase 结束必须生成 snapshot  
2. 每个下一 phase 开始前必须加载 latest snapshot + original command  
3. 若检测到执行目标偏离 original command，必须触发 `REPLAN_TRIGGERED`  
4. 事件层与快照层必须带 `traceId`、`planId`、`phaseId`（如适用）  
5. 文档、接口、前端事件类型需同步更新（与 D5 联动）

### 12.6 验收标准（MVP）

1. 连续 20 个多阶段任务中，原始命令引用完整率 100%  
2. phase 级关键输出可被下一阶段读取成功率 >= 99%  
3. 中断恢复后（审批或服务重启）任务上下文恢复成功率 >= 99%  
4. 发生偏航时可触发并记录 replan 事件，漏报率 = 0  
5. 前端可回放阶段进度与关键产出，事件缺失率 < 1%

