# Plan 可视化落地改造清单（最小改动优先）

## 1. 文档目的

本清单用于将当前「可执行但不可观测」的 Plan MVP，快速提升为「前端可实时可视化」版本。  
目标是：**前端马上能画出“阶段-skill-状态-失败原因-关键输出”关系图**，并可通过 SSE 持续刷新。

---

## 2. 当前现状（基于现有代码）

### 2.1 已具备
- 计划主流程：
  - `PlanOrchestratorServiceImpl.createAndRunPlan(...)`
  - `runPhase(...)`
  - `finalizeAndReport(...)`
- 基础表已写入：
  - `plan_instance`
  - `plan_blueprint`
  - `plan_phase`
  - `plan_node`
  - `plan_report`
- SSE 推送能力：
  - `PlanEventTools.emitPlanEventSse(...)`
  - `LunaStatusPublisher.publishEvent(...)`

### 2.2 未具备 / 不完整
- 前端可直接消费的“图谱快照接口”缺失
- SSE 事件 payload 未统一标准化（字段不齐）
- `plan_event_log` 未系统落库（仅 SSE，不可回放）
- `plan_edge` 未写入（关系边缺失）
- `outputForNext` 仅节点内保存，未在事件中标准输出摘要

---

## 3. 目标定义（本轮最小改动）

### 3.1 前端即时可视化目标（必须达成）
1. 能拿到阶段列表（phase）
2. 能拿到每阶段节点列表（node）
3. 每个节点有：
   - skillName（或 nodeName）
   - status
   - failReason
   - outputForNext（摘要）
4. 能实时订阅事件（SSE）更新节点状态
5. 失败时可看到失败原因

### 3.2 本轮不强求（可后续迭代）
- 复杂 DAG 自动布局（可先按 phase+order 展示）
- checkpoint 回放
- 图形化 diff/replan

---

## 4. 事件协议（前后端统一）

> 建议作为前端绘图和状态更新的唯一输入协议。

### 4.1 统一事件基础字段
- `eventType`：事件类型（如 `PLAN_NODE_SUCCESS`）
- `planId`
- `phaseId`
- `nodeId`
- `status`
- `message`
- `timestamp`

### 4.2 节点事件扩展字段
- `skillName`（优先，若无则 nodeName）
- `nodeType`
- `failReason`（失败才有）
- `errorCode`（失败可选）
- `retryCount`
- `costMs`
- `outputForNext`（建议裁剪摘要，不直接塞大对象）

### 4.3 阶段事件扩展字段
- `phaseOrder`
- `successCount`
- `failCount`

---

## 5. 最小改动落地方案（分两步）

## Step A（优先，前端当天可画图）

### A1. 新增“图谱快照查询接口”
**目的**：前端进入页面先拉一次全量快照，再通过 SSE 增量更新。  
**建议返回结构**（示意）：

```json
{
  "status": "success",
  "data": {
    "planId": "plan-xxx",
    "phases": [
      {
        "phaseId": "plan-xxx:phase-1",
        "phaseOrder": 1,
        "name": "MVP_PHASE_1",
        "status": "SUCCESS",
        "nodes": [
          {
            "nodeId": "node-xxx",
            "nodeName": "mvp-node-...",
            "skillName": "mvp-node-...",
            "nodeType": "TOOL",
            "status": "SUCCESS",
            "failReason": "",
            "outputForNext": {"result":"ok"},
            "costMs": 25
          }
        ]
      }
    ]
  }
}
```

### A2. 标准化 Plan SSE payload
**要求**：
- `PLAN_NODE_RUNNING/SUCCESS/FAILED` 均带上 `skillName`、`failReason`、`outputForNext`（成功）
- `PLAN_PHASE_STARTED/FINISHED` 带 `phaseOrder`、统计信息

### A3. 前端接入策略
1. 页面初始化调用快照接口（全量）
2. 订阅 SSE（增量）
3. 以 `nodeId` 为主键更新节点状态
4. `PLAN_PHASE_FINISHED` 时更新阶段聚合指标

---

## Step B（低成本增强，可紧随其后）

### B1. 事件持久化到 `plan_event_log`
每次 SSE 同时写一条 `plan_event_log`，保证刷新/重连后可追溯。  
最小策略：只写关键事件（phase start/finish, node running/success/failed, report ready）。

### B2. 补写 `plan_edge` 顺序边
先生成简单边：
- 同阶段节点按顺序连边
- 阶段尾节点 -> 下一阶段首节点  
这样前端至少有“关系线”，即使不是复杂 DAG。

---

## 6. 具体文件改造清单（按优先级）

## 6.1 第一优先级（必须改）
1. `src\main\java\org\yilena\luna\service\impl\PlanOrchestratorServiceImpl.java`
   - 标准化 `emitEvent` payload
   - 在节点成功事件中补 `outputForNext` 摘要
   - 在节点失败事件中补 `failReason/errorCode`
   - 在阶段完成事件中补统计字段

2. `src\main\java\org\yilena\luna\controller\PlanOrchestratorController.java`
   - 新增 `GET /luna/api/plan/graph/{planId}`（或 `/snapshot/{planId}`）接口

3. `src\main\java\org\yilena\luna\service\PlanOrchestratorService.java`
   - 新增图谱快照服务方法定义（如 `String getPlanGraph(String planId)`）

## 6.2 第二优先级（建议紧接）
4. `src\main\java\org\yilena\luna\service\impl\PlanOrchestratorServiceImpl.java`
   - 实现 `getPlanGraph(...)`，按 phase -> node 聚合输出

5. `src\main\java\org\yilena\luna\tools\PlanEventTools.java`
   - 增加可选的落库封装（SSE + DB 双写）

## 6.3 第三优先级（增强）
6. `src\main\java\org\yilena\luna\mapper\PlanEventLogMapper.java`
   - 若需复杂查询，可加按 `planId+createdAt` 查询方法（可选）

7. `src\main\java\org\yilena\luna\mapper\PlanNodeMapper.java` / `PlanPhaseMapper.java`
   - 若需要专门图谱查询 SQL，可新增方法（可选）

---

## 7. 前端联调字段约定（建议锁定）

### 7.1 Node 状态枚举
- `PENDING`
- `RUNNING`
- `SUCCESS`
- `FAILED`
- `BLOCKED`
- `APPROVAL_PENDING`
- `SKIPPED`

### 7.2 Phase 状态枚举
- `PENDING`
- `RUNNING`
- `SUCCESS`
- `FAILED`

### 7.3 事件类型（首批）
- `PLAN_CREATED`
- `PLAN_PHASE_STARTED`
- `PLAN_NODE_RUNNING`
- `PLAN_NODE_SUCCESS`
- `PLAN_NODE_FAILED`
- `PLAN_PHASE_FINISHED`
- `PLAN_REPORT_READY`

---

## 8. 验收标准（可测试）

## 8.1 后端接口验收
- 调用 `POST /luna/api/plan/run` 返回 `planId`
- 调用 `GET /luna/api/plan/graph/{planId}` 返回 phase+node 全量结构
- 任一失败节点可在快照内看到 `failReason`

## 8.2 SSE 验收
- 前端能收到 `PLAN_NODE_RUNNING/SUCCESS/FAILED`
- `PLAN_NODE_FAILED` 必含 `failReason`
- `PLAN_NODE_SUCCESS` 必含 `outputForNext`（可为空对象但字段存在）

## 8.3 可视化验收
- 前端图中可展示：
  - 阶段数量
  - 每阶段 skill（节点）名称
  - 成败状态颜色
  - 失败原因 tooltip
  - 关键输出摘要（成功节点）

---

## 9. 风险与规避

1. **outputForNext 体积过大**
   - 规避：事件中只传摘要字段（如 top-level keys + 截断文本）

2. **SSE 丢事件**
   - 规避：前端定时或关键节点后补拉 `graph/{planId}` 快照校准

3. **高并发时事件乱序**
   - 规避：payload 带 `timestamp` + `phaseOrder` + `nodeId`，前端按时间+状态机兜底

---

## 10. 推荐实施顺序（1~2 天）

### Day 1
- 改 `PlanOrchestratorServiceImpl` 事件字段
- 加 `graph/{planId}` 接口
- 前端先完成“全量+增量”渲染

### Day 2
- 事件落库 `plan_event_log`
- 补简单 `plan_edge`
- 加前端“历史回放/断线重载”能力

---

## 11. 最终结论

你目前看到“只有任务完成提示、其他图谱信息不足”是**当前 MVP 设计导致**，不是异常。  
按本清单做最小改动后，前端可以很快实现你要求的可视化能力：
- 看到几个阶段
- 每阶段用了什么 skill
- skill 成败与失败原因
- 成功后传给下阶段的关键信息
- 并且通过 SSE 实时刷新。
