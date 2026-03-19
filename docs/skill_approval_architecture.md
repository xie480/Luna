# MCP Skill 敏感操作审批流架构设计

## 1. 需求背景
当前系统中的 `McpSkill` 具有不同的敏感度等级 (`Sensitivity`)。对于 `LOW` 等级的操作，Agent 可以自主执行；但对于 `MEDIUM` 和 `HIGH` 等级的操作，必须向用户发起审批请求。
无论用户同意还是拒绝：
1. 必须记录详细的系统日志。
2. 必须将用户的决定（及执行结果）作为上下文反馈给大模型（LLM），以便大模型根据结果继续对话（例如：执行成功后汇报结果，或被拒绝后向用户致歉并询问下一步指示）。

## 2. 核心挑战
大模型的工具调用（Tool Calling）默认是**同步阻塞**的。当 LLM 决定调用工具时，它会等待工具返回结果。如果在此期间等待用户点击“同意”，可能会导致 HTTP 请求超时或长时间占用线程。

## 3. 架构方案设计：异步中断与状态恢复机制

为了保证系统的高并发和稳定性，我们采用**基于 Redis 的异步状态机**方案。

### 3.1 核心流程图

```text
[LLM 决策] -> 输出 ToolCall (Skill: delete_database)
      |
      v
[ExecutionGate 安全网关]
      |-- 检查 Sensitivity == HIGH
      |-- 拦截执行，生成唯一的 ApprovalTaskID
      |-- 将 ToolCall 上下文 (参数、SessionID等) 存入 Redis (设置过期时间，如 10 分钟)
      |
      v
[状态推送] -> 通过 SSE/WebSocket 向前端推送审批请求: {"type": "APPROVAL_REQUEST", "taskId": "xxx", "skillName": "..."}
      |
      v
[当前请求结束] -> 后端向 LLM 历史记录中暂存一个“等待用户授权”的内部状态，当前 HTTP 线程释放。

==================== (用户思考与操作时间) ====================

[前端操作] -> 用户点击 "同意" 或 "拒绝"
      |
      v
[审批回调接口 API] -> POST /mcp/skills/approve { taskId: "xxx", approved: true/false }
      |
      v
[状态恢复与执行]
      |-- 从 Redis 取出 ToolCall 上下文
      |-- 记录 LunaLog (用户同意/拒绝了操作)
      |-- IF (拒绝):
      |       生成模拟结果: {"status": "rejected", "reason": "User denied"}
      |-- IF (同意):
      |       调用 ReflectionToolExecutor 真正执行 Skill
      |       获取真实结果: {"status": "success", "data": "..."}
      |
      v
[唤醒 LLM] -> 将上述结果封装为 ToolExecutionResultMessage，追加到历史记录中。
      |
      v
[LLM 重新生成] -> LLM 看到结果后，生成最终回复（如：“已为您完成删除” 或 “好的，我已取消该操作”）。
      |
      v
[状态推送] -> 通过 SSE 推送 LLM 的最终回复给前端。
```

### 3.2 核心组件设计

#### 1. `ExecutionGate` (执行网关改造)
*   **职责**: 拦截工具调用，判断敏感度。
*   **逻辑**: 如果是 `MEDIUM` 或 `HIGH`，抛出特定的自定义异常（如 `NeedApprovalException`），或者返回特定的中断信号。

#### 2. `ApprovalService` (审批服务)
*   **职责**: 管理审批任务的生命周期。
*   **存储**: 使用 Redis 存储待审批任务。
    *   `Key`: `luna:approval:{taskId}`
    *   `Value`: 包含 `sessionId`, `skillName`, `beanName`, `methodName`, `argsJson` 的 JSON 对象。
    *   `TTL`: 建议设置 5-10 分钟过期时间，超时自动视为拒绝。

#### 3. `ApprovalController` (审批回调接口)
*   **接口**: `POST /mcp/skills/approval`
*   **参数**:
    ```json
    {
      "taskId": "uuid-string",
      "approved": true,
      "rejectReason": "可选，用户填写的拒绝原因"
    }
    ```

#### 4. `AgentService` (大模型对话服务改造)
*   **职责**: 处理中断和恢复。
*   **中断处理**: 捕获到 `NeedApprovalException` 时，不向 LLM 报错，而是通过 SSE 告诉前端“请审批”，并保存当前对话历史。
*   **恢复处理**: 提供一个内部方法 `resumeConversation(sessionId, toolCallId, resultJson)`，用于在审批完成后，将结果塞回历史记录，并主动触发一次 LLM 的 `chat()` 请求。

### 3.3 日志记录 (LunaLog)
在 `ApprovalService` 处理回调时，统一写入日志：
*   **同意**: `LogType.USER_ACTION`, content: "用户批准了高危操作: [SkillName]"
*   **拒绝**: `LogType.USER_ACTION`, content: "用户拒绝了高危操作: [SkillName]"

## 4. 为什么选择这种架构？
1. **非阻塞**: 不会因为等待用户点击而耗尽服务器的虚拟线程或 HTTP 连接池。
2. **容灾性**: 即使在等待审批期间服务器重启，只要 Redis 数据还在，用户点击审批后依然可以恢复执行。
3. **LLM 认知连贯**: LLM 发出调用指令 -> 收到明确的成功/拒绝反馈 -> 给出最终回答。这完全符合 LangChain4j 和 OpenAI 的 Tool Calling 规范。

## 5. 下一步开发计划
如果确认此架构，我们将按以下顺序编写代码：
1. 创建 `ApprovalTask` 实体和 Redis 存储逻辑。
2. 编写 `ApprovalController` 和 `ApprovalService`。
3. 改造 `ExecutionGate`，触发审批中断。
4. 改造 `AgentService`，支持从中断处恢复对话。
