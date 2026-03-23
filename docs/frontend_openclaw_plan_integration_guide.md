# 前端接入指南：OpenClaw 计划编排与异步技能能力（MVP）

## 1. 目标与范围

本文档用于指导前端完成以下能力接入：

1. 发起计划执行（创建并运行）  
2. 订阅并展示计划执行过程（SSE）  
3. 展示阶段/节点状态、失败原因、耗时  
4. 接收异步技能完成事件并更新 UI  
5. 显示并打开最终任务报告  

> 说明：本文聚焦“可用 MVP”，不要求一次实现完整 DAG 画布。

---

## 2. 后端接口清单

## 2.1 创建并执行计划（主入口）
- **Method**: `POST`
- **Path**: `/luna/api/plan/run`
- **Auth**: 需要 `Authorization: Bearer <token>`

### 请求体
```json
{
  "sessionId": "optional-session-id",
  "userGoal": "帮我整理项目并生成报告"
}
```

### 说明
- 后端优先使用 JWT jti 作为稳定 sessionId  
- `sessionId` 字段可传可不传（建议保留，兼容兜底）

### 成功响应示例
```json
{
  "status": "success",
  "planId": "plan-123456789",
  "phaseResults": [
    {
      "phaseId": "phase-1",
      "phaseOrder": 1,
      "result": {
        "status": "success",
        "planId": "plan-123456789",
        "phaseId": "phase-1",
        "successCount": 1,
        "failCount": 0,
        "costMs": 43
      }
    }
  ],
  "reportResult": {
    "status": "success",
    "planId": "plan-123456789",
    "finalStatus": "SUCCESS",
    "writeResult": {
      "status": "success",
      "data": {
        "reportPath": "D:/.../data/reports/plan-123456789.html",
        "reportUrl": "file:///D:/.../data/reports/plan-123456789.html"
      }
    },
    "openResult": {
      "status": "success",
      "data": {
        "openResult": "SUCCESS"
      }
    }
  },
  "message": "计划多阶段执行成功并生成报告"
}
```

---

## 2.2 执行单阶段（调试/补偿）
- **Method**: `POST`
- **Path**: `/luna/api/plan/phase/run`

### 请求体
```json
{
  "planId": "plan-123456789",
  "phaseId": "phase-1"
}
```

---

## 2.3 收尾并生成报告（调试/补偿）
- **Method**: `POST`
- **Path**: `/luna/api/plan/report/finalize`

### 请求体
```json
{
  "planId": "plan-123456789"
}
```

---

## 2.4 SSE 订阅接口
- **Method**: `GET`
- **Path**: `/api/luna/status/stream`
- **类型**: `text/event-stream`

---

## 3. 事件契约（前端必接）

前端统一监听以下事件，并按 `eventType` 分发处理：

## 3.1 计划主链路事件

1. `PLAN_CREATED`  
2. `PLAN_PHASE_STARTED`  
3. `PLAN_NODE_RUNNING`  
4. `PLAN_NODE_SUCCESS`  
5. `PLAN_NODE_FAILED`  
6. `PLAN_PHASE_FINISHED`  
7. `PLAN_REPORT_READY`

### 事件 payload 常见字段
- `planId`
- `phaseId`
- `nodeId`
- `status`
- `message`
- `timestamp`
- （可选）`costMs` / `retryCount` / `errorCode` / `failReason` / `reportPath`

---

## 3.2 异步技能事件
- `SKILL_ASYNC_RESULT`

### payload 字段（已统一）
- `taskId`
- `skillName`
- `status`（`COMPLETED` / `FAILED`）
- `success`（boolean）
- `message`
- `errorCode`
- `error`
- `result`
- `costMs`
- `timestamp`

---

## 3.3 通用状态事件
- `luna-status`

### payload 示例
```json
{
  "eventType": "luna-status",
  "status": "THINKING",
  "message": "Luna 正在思考...",
  "traceId": "",
  "taskId": "",
  "timestamp": 1730000000000
}
```

---

## 4. 前端需要新增/修改的功能点

## 4.1 新增：计划执行入口
建议新增“计划执行”面板，至少包含：
- 用户目标输入框 `userGoal`
- 执行按钮（调用 `/luna/api/plan/run`）
- 当前计划 ID 显示
- 最终结果摘要（成功/失败、阶段数量、报告地址）

---

## 4.2 新增：计划执行监控视图（MVP）
建议两栏布局：

### 左侧：阶段列表
字段：
- `phaseId`
- `phaseOrder`
- `status`
- `costMs`

### 右侧：节点执行流
字段：
- `nodeId`
- `phaseId`
- `status`（颜色区分）
- `message`
- `costMs`
- `retryCount`
- `errorCode` / `failReason`

---

## 4.3 修改：全局 SSE 管理器
如果前端已有 SSE 模块，需要做两类扩展：

1. 事件类型路由扩展  
   - 新增 `PLAN_*` 与 `SKILL_ASYNC_RESULT` 分支
2. 状态存储扩展  
   - 增加 `planRuntimeStore`（按 `planId` 聚合）

---

## 4.4 修改：审批/异步消息中心（如果已有）
将 `SKILL_ASYNC_RESULT` 纳入消息中心：
- 成功提示：技能名 + 耗时
- 失败提示：技能名 + errorCode + error

---

## 4.5 新增：报告打开入口
报告来源优先级建议：
1. `PLAN_REPORT_READY` 事件里的 `reportPath`
2. `/plan/run` 响应里的 `reportResult.writeResult.data.reportUrl/reportPath`

前端按钮文案建议：
- `打开任务报告`
- `复制报告路径`

---

## 5. 前端状态模型建议（可直接落地）

## 5.1 计划运行态（建议结构）
```json
{
  "planId": "plan-xxx",
  "status": "RUNNING",
  "createdAt": 1730000000000,
  "updatedAt": 1730000005000,
  "phases": {
    "phase-1": {
      "phaseId": "phase-1",
      "status": "RUNNING",
      "costMs": 0
    }
  },
  "nodes": {
    "node-1": {
      "nodeId": "node-1",
      "phaseId": "phase-1",
      "status": "SUCCESS",
      "message": "节点执行成功",
      "costMs": 36,
      "retryCount": 0
    }
  },
  "report": {
    "ready": false,
    "reportPath": "",
    "reportUrl": ""
  },
  "errors": []
}
```

---

## 5.2 事件到状态的映射规则（关键）
- `PLAN_CREATED`：创建 plan runtime 与初始节点  
- `PLAN_PHASE_STARTED`：阶段置 `RUNNING`  
- `PLAN_NODE_RUNNING`：节点置 `RUNNING`  
- `PLAN_NODE_SUCCESS`：节点置 `SUCCESS`，记录耗时  
- `PLAN_NODE_FAILED`：节点置 `FAILED`，记录错误码  
- `PLAN_PHASE_FINISHED`：阶段置 `SUCCESS/FAILED`  
- `PLAN_REPORT_READY`：`report.ready=true` 并写入路径  

---

## 6. 接口调用与异常处理建议

## 6.1 鉴权失败
- HTTP `401`：跳转登录或刷新 token
- 对用户提示：`登录状态已失效，请重新登录`

## 6.2 参数错误
- HTTP `400`：直接展示后端 message
- 常见：`userGoal 不能为空`

## 6.3 服务异常
- HTTP `500`：展示通用错误，保留“查看详细”开关

---

## 6.4 幂等与重复点击
- 执行按钮防抖/禁用（请求中禁用）
- 同一时间只允许一个活跃计划（建议，MVP 阶段）

---

## 7. 前端联调步骤（建议）

1. 登录获取 token  
2. 打开 SSE 连接 `/api/luna/status/stream`  
3. 调用 `/luna/api/plan/run`  
4. 观察事件流是否按顺序到达  
5. 验证最终 `PLAN_REPORT_READY` 是否到达  
6. 点击“打开任务报告”验证路径可用

---

## 8. 验收清单（前端）

1. 能成功发起计划执行请求  
2. 能实时展示阶段/节点状态变更  
3. 失败节点可见错误信息（errorCode/message）  
4. 能接收并展示 `SKILL_ASYNC_RESULT`  
5. 能展示并打开报告入口  
6. 页面刷新后不崩溃（可选：保留最近一次 planId）

---

## 9. 后续增强建议（非本次必做）

1. 节点 DAG 图形化（替代列表）  
2. 阶段折叠与节点详情抽屉  
3. 失败节点“仅重跑本阶段”按钮  
4. 异步任务中心（历史任务分页）  
5. 报告内嵌预览（iframe/新窗口）

---

## 10. 前后端字段对齐注意事项

1. `status` 存在多套语义（计划状态、节点状态、异步任务状态），前端不要混用同一枚举  
2. `result` 字段可能是对象或字符串（失败场景），渲染时做类型判断  
3. `reportPath` 可能是本地绝对路径，浏览器端不可直接访问时要提示“请在服务端机器打开”  
4. SSE 事件可能乱序到达（网络抖动），建议按 `timestamp` 做弱排序或“后到覆盖前到”策略  

---

如需，我可以下一步补一份“前端状态机伪代码（Vue/React 双版本）”给你前端同事直接开工。
