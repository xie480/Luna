# MCP 架构 v2.1 前端迁移指南：敏感度与审批配置迁移

## 1. 变更背景
在之前的版本中，我们将“敏感度 (`sensitivity`)”和“是否需要审批 (`requiresApproval`)”配置在了复合技能 (`Skill`) 上。
经过架构重新评估，我们认为**风险的根源在于底层的原子操作**（例如：`web_search` 是低风险，但 `delete_file` 是高风险）。因此，为了实现更精准的安全拦截，后端已将这两个字段从 `Skill` 迁移到了 `Tool`。

前端需要配合调整资源注册表单、数据接口定义以及部分逻辑。

## 2. 数据结构 (TypeScript 接口) 变更

请更新前端项目中的相关 Interface：

### 2.1 McpTool (原子工具) - **新增字段**
```typescript
export interface McpTool {
  id?: string;
  name: string;
  description: string;
  version?: string;
  owner?: string;
  beanName: string;
  methodName: string;
  inputSchema: string;
  outputSchema?: string;
  
  // --- 新增字段 ---
  requiresApproval?: boolean; // 是否需要人工审批
  sensitivity?: 'LOW' | 'MEDIUM' | 'HIGH'; // 敏感度等级
  
  embedding?: string;
  createdAt?: string;
  updatedAt?: string;
}
```

### 2.2 McpSkill (复合技能) - **移除字段**
```typescript
export interface McpSkill {
  id?: string;
  name: string;
  description: string;
  version?: string;
  owner?: string;
  beanName: string;
  methodName: string;
  inputSchema: string;
  outputSchema?: string;
  runMode?: 'SYNC' | 'ASYNC';
  
  // --- 移除字段 ---
  // requiresApproval?: boolean; (已移除)
  // sensitivity?: 'LOW' | 'MEDIUM' | 'HIGH'; (已移除)
  
  embedding?: string;
  createdAt?: string;
  updatedAt?: string;
}
```

### 2.3 Resource (统一资源 DTO) - **结构不变，语义变化**
`Resource` 接口结构保持不变，但在 `/mcp/resources` 和 `/mcp/search` 接口返回的数据中：
- 当 `type === 'TOOL'` 时，`requiresApproval` 和 `sensitivity` 将读取真实的数据库配置。
- 当 `type === 'SKILL'` 时，`requiresApproval` 将固定返回 `false`，`sensitivity` 固定返回 `'LOW'`。

## 3. UI 交互调整建议

### 3.1 资源注册/编辑表单
1. **Tool 表单**：请在“注册/编辑原子工具”的表单中，增加「是否需要审批 (Switch/Checkbox)」和「敏感度等级 (Select: LOW/MEDIUM/HIGH)」的表单项。
2. **Skill 表单**：请从“注册/编辑复合技能”的表单中，移除上述两个表单项。Skill 现在仅保留「执行模式 (SYNC/ASYNC)」的配置。

### 3.2 审批流 (Approval Modal) 兼容说明
后端的审批拦截网关 (`ExecutionGate`) 现在会拦截高敏感度的 **Tool**。
当触发 SSE `APPROVAL_REQUEST` 事件时，推送的 `ApprovalTask` 数据结构**没有变化**。
> **注意**：为了保持向下兼容，推送的 payload 中依然使用 `skillName` 字段，但它现在实际代表的是被拦截的 **Tool 的名称**。前端的审批弹窗逻辑无需修改代码，只需知晓弹窗中显示的名称可能是 Tool 即可。

## 4. 接口请求示例对比

**注册 Tool (POST `/mcp/tools`)**
```json
// 现在的请求体需要包含敏感度配置
{
  "name": "delete_database",
  "description": "删除数据库",
  "beanName": "dbTools",
  "methodName": "drop",
  "inputSchema": "...",
  "requiresApproval": true,
  "sensitivity": "HIGH"
}
```

**注册 Skill (POST `/mcp/skills`)**
```json
// 现在的请求体不再包含敏感度配置
{
  "name": "export_data_async",
  "description": "异步导出数据",
  "beanName": "exportSkill",
  "methodName": "execute",
  "inputSchema": "...",
  "runMode": "ASYNC"
}
```

请前端同学按照此文档更新对应的表单组件和 API 传参逻辑。
