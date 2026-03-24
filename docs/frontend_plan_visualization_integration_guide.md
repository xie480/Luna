# 前端改造说明（基于 Plan 可视化后端改造）

## 1. 文档目的

本文档面向前端同事，说明后端当前已完成的 Plan 可视化能力，以及前端需要配套做的修改、优化和重构点。  
目标：前端可稳定实现 **“全量快照 + SSE 增量更新”** 的计划图谱展示，并支持失败原因和关键输出摘要可视化。

---

## 2. 后端现状（你可以直接对接）

后端已提供并可用：

1. **图谱快照接口**
   - `GET /luna/api/plan/graph/{planId}`

2. **计划执行接口**
   - `POST /luna/api/plan/run`

3. **SSE 通道**
   - 当前状态流端点：`GET /api/luna/status/stream`
   - 计划事件会通过统一 SSE 管道下发（eventName 为 `PLAN_*`）

4. **事件双写**
   - 后端已做 `SSE + plan_event_log` 双写（用于可追溯）

5. **边关系补写**
   - 后端已补 `plan_edge` 基础顺序边（同阶段顺序 + 阶段衔接）

---

## 3. 前端必须改造项（必须做）

## 3.1 数据流改造为：全量 + 增量

页面进入后，执行顺序必须为：

1. 调用 `POST /luna/api/plan/run` 拿 `planId`（或从业务上下文已有 `planId`）
2. 立刻调用 `GET /luna/api/plan/graph/{planId}` 拉一次全量快照
3. 建立 SSE 订阅，持续接收 `PLAN_*` 事件
4. 以 `nodeId` 为主键做增量更新（不可整图全量重绘）

---

## 3.2 图谱数据模型统一（建议前端内部标准）

建议建立前端统一状态仓库（Redux/Pinia/Zustand/Vuex 任一）：

```ts
type PlanGraphState = {
  planId: string
  phases: Record<string, PhaseVM>
  phaseOrder: string[] // phaseId ordered
  nodes: Record<string, NodeVM>
  edges: EdgeVM[]
  lastSyncAt: number
}

type PhaseVM = {
  phaseId: string
  phaseOrder: number
  name: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  successCount: number
  failCount: number
  nodeIds: string[]
}

type NodeVM = {
  nodeId: string
  phaseId: string
  nodeName: string
  skillName: string
  nodeType: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'BLOCKED' | 'APPROVAL_PENDING' | 'SKIPPED'
  failReason: string
  errorCode?: string
  retryCount: number
  costMs: number
  outputForNext: any
  timestamp?: number
}

type EdgeVM = {
  fromNodeId: string
  toNodeId: string
  conditionExpr?: string
}
```

---

## 3.3 SSE 事件处理器（必须按事件类型分流）

首批事件类型（后端已对齐）：

- `PLAN_CREATED`
- `PLAN_PHASE_STARTED`
- `PLAN_NODE_RUNNING`
- `PLAN_NODE_SUCCESS`
- `PLAN_NODE_FAILED`
- `PLAN_PHASE_FINISHED`
- `PLAN_REPORT_READY`

### 事件基础字段（通用）
- `eventType`
- `planId`
- `phaseId`
- `nodeId`
- `status`
- `message`
- `timestamp`

### 节点事件关键字段
- `skillName`
- `nodeType`
- `failReason`
- `errorCode`
- `retryCount`
- `costMs`
- `outputForNext`

### 阶段事件关键字段
- `phaseOrder`
- `successCount`
- `failCount`

---

## 4. 前端渲染与交互改造建议

## 4.1 图布局策略（本轮最小可行）

本轮不强制 DAG 自动布局，建议：

1. 按 `phaseOrder` 分栏（每个阶段一列）
2. 每列内节点按默认顺序渲染（可按 nodeId 或后端返回顺序）
3. 边使用后端返回的 `edges` 画连线
4. 状态色：
   - PENDING: 灰
   - RUNNING: 蓝
   - SUCCESS: 绿
   - FAILED: 红
   - 其他状态按你哋 UI 规范扩展

---

## 4.2 节点卡片信息（必须展示）

每个节点至少展示：

- `skillName`（兜底 `nodeName`）
- `status`
- `costMs`（可选显示）
- 失败时 tooltip/展开区显示 `failReason`
- 成功时显示 `outputForNext` 摘要（不要默认展开大 JSON）

---

## 4.3 阶段头部聚合信息（建议）

每个 Phase 标题区建议展示：

- 阶段名称 + `phaseOrder`
- 阶段状态
- `successCount / failCount`

当收到 `PLAN_PHASE_FINISHED` 时即时刷新该统计。

---

## 5. 关键实现细节（避免踩坑）

## 5.1 事件去重与乱序处理

后端事件带 `timestamp`，前端要做：

1. 为节点维护 `lastEventTs`
2. 新事件若 `timestamp < lastEventTs`，按策略丢弃或仅补字段
3. 状态流转建议做保护（例如 SUCCESS 后不应回退 RUNNING，除非你支持重试可视化）

---

## 5.2 断线重连策略（必须）

SSE 可能断开，前端要：

1. 自动重连（指数退避：1s/2s/5s...上限 30s）
2. 每次重连成功后，补拉一次 `GET /luna/api/plan/graph/{planId}` 校准状态
3. 页面后台切回前台时亦建议触发一次校准

---

## 5.3 空字段兜底

事件或快照中可能出现空值，前端统一兜底：

- `skillName || nodeName || 'unknown-node'`
- `failReason || ''`
- `outputForNext || {}`
- `retryCount ?? 0`
- `costMs ?? 0`

---

## 6. 推荐前端重构清单（按优先级）

## P0（必须当天完成）
1. 建立 `PlanGraphStore`
2. 接入 `graph/{planId}` 全量快照
3. 接入 SSE 并完成 `PLAN_NODE_*`/`PLAN_PHASE_*` 增量更新
4. 失败原因、输出摘要可视化

## P1（建议紧接）
1. SSE 断线重连 + 自动快照校准
2. 节点更新最小重绘（提升性能）
3. 事件日志面板（可选读取后端历史接口，如你哋后续加）

## P2（后续优化）
1. 更优 DAG 自动布局
2. 历史回放模式（依赖事件查询接口）
3. diff/replan 可视化

---

## 7. 对接示例（伪代码）

```ts
async function initPlanVisualization(userGoal: string) {
  const runRes = await api.post('/luna/api/plan/run', { userGoal })
  const planId = runRes?.planId || runRes?.data?.planId
  if (!planId) throw new Error('missing planId')

  await syncGraphSnapshot(planId)
  startSse(planId)
}

async function syncGraphSnapshot(planId: string) {
  const res = await api.get(`/luna/api/plan/graph/${planId}`)
  planGraphStore.replaceFromSnapshot(res.data)
}

function startSse(planId: string) {
  const es = new EventSource('/api/luna/status/stream')

  const bind = (eventType: string) => {
    es.addEventListener(eventType, (ev: MessageEvent) => {
      const payload = JSON.parse(ev.data || '{}')
      if (payload.planId !== planId) return
      planGraphStore.applyEvent(payload)
    })
  }

  ;[
    'PLAN_CREATED',
    'PLAN_PHASE_STARTED',
    'PLAN_NODE_RUNNING',
    'PLAN_NODE_SUCCESS',
    'PLAN_NODE_FAILED',
    'PLAN_PHASE_FINISHED',
    'PLAN_REPORT_READY'
  ].forEach(bind)

  es.onerror = async () => {
    es.close()
    await wait(2000)
    await syncGraphSnapshot(planId) // 先校准再重连
    startSse(planId)
  }
}
```

---

## 8. 验收清单（前端自测）

1. 运行计划后可见 phase + node 初始图
2. 节点运行中（RUNNING）状态实时变更
3. 成功节点可见 `outputForNext` 摘要
4. 失败节点可见 `failReason`
5. 阶段完成时 `successCount/failCount` 更新
6. 断开 SSE 后自动恢复并校准成功

---

## 9. 已知注意事项

1. SSE 是统一状态流，可能混入非计划事件（例如 `luna-status`、`SKILL_ASYNC_RESULT`），前端必须按 `eventType` 过滤。
2. 多计划并行场景下，必须按 `planId` 过滤事件，避免串图。
3. `outputForNext` 是摘要，不保证完整原始对象，详细信息如需可后续加节点详情接口。

---

## 10. 总结

你哋前端只要按本文档落地 **“快照初始化 + SSE 增量更新 + 断线校准”**，就能完整展示：

- 几个阶段
- 每阶段有哪些技能节点
- 节点实时状态
- 失败原因
- 成功输出摘要
- 基础关系连线

本轮目标已能支持上线级可视化 MVP。
