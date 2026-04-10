package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.McpPromptCatalog;
import org.yilena.luna.entity.McpResourceCatalog;
import org.yilena.luna.entity.McpServerRegistry;
import org.yilena.luna.entity.McpToolCatalog;
import org.yilena.luna.entity.McpToolImplMapping;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.WorkflowTemplate;
import org.yilena.luna.service.McpService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@Tag(name = "MCP 接口", description = "提供能力目录查询、协议调用和迁移写入接口")
/**
 * MCP 控制器，负责暴露能力目录查询、协议风格调用以及迁移写入接口。
 */
public class McpController {

    /**
     * MCP 服务，负责具体的目录管理和协议调用逻辑。
     */
    private final McpService mcpService;

    @PostMapping("/tools")
    /**
     * 保留旧版工具注册接口，统一返回退役提示，避免旧调用方误以为写入成功。
     */
    @Operation(summary = "旧版工具注册接口", description = "该接口已退役，调用后会返回 410 并提示使用新的工具目录迁移接口")
    public ResponseEntity<Map<String, Object>> registerTool() {
        return legacyGone("legacy /mcp/tools write API retired, use /mcp/migrate/tool-catalog and capability_registry path");
    }

    @PutMapping("/tools")
    /**
     * 保留旧版工具更新接口，统一返回退役提示。
     */
    @Operation(summary = "旧版工具更新接口", description = "该接口已退役，调用后会返回 410 并提示使用新的工具目录迁移接口")
    public ResponseEntity<Map<String, Object>> updateTool() {
        return legacyGone("legacy /mcp/tools write API retired, use /mcp/migrate/tool-catalog and capability_registry path");
    }

    @DeleteMapping("/tools/{id}")
    /**
     * 保留旧版工具删除接口，统一返回退役提示。
     */
    @Operation(summary = "旧版工具删除接口", description = "该接口已退役，调用后会返回 410 并提示使用新的工具目录迁移接口")
    public ResponseEntity<Map<String, Object>> deleteTool(@PathVariable Long id) {
        return legacyGone("legacy /mcp/tools write API retired, use /mcp/migrate/tool-catalog and capability_registry path");
    }

    @PostMapping("/skills")
    /**
     * 保留旧版技能注册接口，统一返回退役提示。
     */
    @Operation(summary = "旧版技能注册接口", description = "该接口已退役，调用后会返回 410 并提示使用新的工作流或提示词迁移接口")
    public ResponseEntity<Map<String, Object>> registerSkill() {
        return legacyGone("legacy /mcp/skills write API retired, use /mcp/migrate/workflow-template or /mcp/migrate/prompt-catalog");
    }

    @PutMapping("/skills")
    /**
     * 保留旧版技能更新接口，统一返回退役提示。
     */
    @Operation(summary = "旧版技能更新接口", description = "该接口已退役，调用后会返回 410 并提示使用新的工作流或提示词迁移接口")
    public ResponseEntity<Map<String, Object>> updateSkill() {
        return legacyGone("legacy /mcp/skills write API retired, use /mcp/migrate/workflow-template or /mcp/migrate/prompt-catalog");
    }

    @DeleteMapping("/skills/{id}")
    /**
     * 保留旧版技能删除接口，统一返回退役提示。
     */
    @Operation(summary = "旧版技能删除接口", description = "该接口已退役，调用后会返回 410 并提示使用新的工作流或提示词迁移接口")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable Long id) {
        return legacyGone("legacy /mcp/skills write API retired, use /mcp/migrate/workflow-template or /mcp/migrate/prompt-catalog");
    }

    @GetMapping("/resources")
    /**
     * 查询系统当前已注册的全部能力资源。
     */
    @Operation(summary = "查询全部能力", description = "返回系统当前已注册的全部能力资源列表")
    public ResponseEntity<List<Resource>> listAll() {
        return ResponseEntity.ok(mcpService.listAll());
    }

    @GetMapping("/resources/{id}")
    /**
     * 根据资源主键查询单个能力详情。
     */
    @Operation(summary = "查询能力详情", description = "根据能力资源 ID 返回单个能力的详细信息")
    public ResponseEntity<Resource> getById(@PathVariable Long id) {
        return ResponseEntity.ok(mcpService.getResourceById(id));
    }

    @PostMapping("/search")
    /**
     * 根据关键字搜索能力资源，便于前端做能力发现。
     */
    @Operation(summary = "搜索能力", description = "根据查询关键字搜索可用能力资源")
    public ResponseEntity<List<Resource>> search(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(mcpService.searchResources(body.get("query")));
    }

    @GetMapping("/tools/list")
    /**
     * 查询指定 MCP 服务或全部服务下的工具目录。
     */
    @Operation(summary = "查询工具目录", description = "按 serverCode 查询 MCP 工具列表；不传则返回全部工具")
    public ResponseEntity<?> listTools(@RequestParam(required = false) String serverCode) {
        return ResponseEntity.ok(mcpService.listTools(serverCode));
    }

    @PostMapping("/tools/call")
    /**
     * 发起一次 MCP 工具调用。
     *
     * 该接口依赖请求体中的服务编码、工具名称和参数 JSON，适用于调试或协议转发场景。
     */
    @Operation(summary = "调用工具", description = "根据服务编码、工具名称和参数 JSON 发起一次 MCP 工具调用")
    public ResponseEntity<?> callTool(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(mcpService.callTool(
                body.get("serverCode"),
                body.get("toolName"),
                body.get("argumentsJson")
        ));
    }

    @GetMapping("/prompts/list")
    /**
     * 查询指定服务或全部服务下的提示词目录。
     */
    @Operation(summary = "查询提示词目录", description = "按 serverCode 查询 MCP 提示词列表；不传则返回全部提示词")
    public ResponseEntity<?> listPrompts(@RequestParam(required = false) String serverCode) {
        return ResponseEntity.ok(mcpService.listPrompts(serverCode));
    }

    @PostMapping("/prompts/get")
    /**
     * 读取指定提示词并可携带参数完成模板渲染。
     */
    @Operation(summary = "读取提示词", description = "根据服务编码、提示词名称和参数 JSON 读取指定 MCP 提示词")
    public ResponseEntity<?> getPrompt(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(mcpService.getPrompt(
                body.get("serverCode"),
                body.get("promptName"),
                body.get("argumentsJson")
        ));
    }

    @GetMapping("/resources/list")
    /**
     * 查询指定服务或全部服务下的资源目录。
     */
    @Operation(summary = "查询资源目录", description = "按 serverCode 查询 MCP 资源列表；不传则返回全部资源")
    public ResponseEntity<?> listResources(@RequestParam(required = false) String serverCode) {
        return ResponseEntity.ok(mcpService.listResources(serverCode));
    }

    @PostMapping("/resources/read")
    /**
     * 按资源 URI 读取 MCP 资源内容。
     */
    @Operation(summary = "读取资源内容", description = "根据服务编码和资源 URI 读取指定 MCP 资源")
    public ResponseEntity<?> readResource(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(mcpService.readResource(
                body.get("serverCode"),
                body.get("resourceUri")
        ));
    }

    @PostMapping("/catalog/sync")
    /**
     * 触发能力目录同步，刷新本地缓存的 MCP 能力信息。
     */
    @Operation(summary = "同步能力目录", description = "从已接入的 MCP 服务同步最新的能力目录信息")
    public ResponseEntity<?> syncCatalog() {
        return ResponseEntity.ok(mcpService.syncCapabilityCatalog());
    }

    @PostMapping("/migrate/server-registry")
    /**
     * 全字段写入或更新 MCP 服务注册表记录。
     */
    @Operation(summary = "写入服务注册表", description = "按全字段方式写入或更新 mcp_server_registry 记录")
    public ResponseEntity<McpServerRegistry> upsertServerRegistry(@RequestBody McpServerRegistry registry) {
        return ResponseEntity.ok(mcpService.upsertServerRegistry(registry));
    }

    @PostMapping("/migrate/tool-catalog")
    /**
     * 全字段写入或更新工具目录记录。
     */
    @Operation(summary = "写入工具目录", description = "按全字段方式写入或更新 mcp_tool_catalog 记录")
    public ResponseEntity<McpToolCatalog> upsertToolCatalog(@RequestBody McpToolCatalog toolCatalog) {
        return ResponseEntity.ok(mcpService.upsertToolCatalog(toolCatalog));
    }

    @PostMapping("/migrate/tool-impl-mapping")
    /**
     * 全字段写入或更新工具实现映射记录。
     */
    @Operation(summary = "写入工具实现映射", description = "按全字段方式写入或更新 mcp_tool_impl_mapping 记录")
    public ResponseEntity<McpToolImplMapping> upsertToolImplMapping(@RequestBody McpToolImplMapping mapping) {
        return ResponseEntity.ok(mcpService.upsertToolImplMapping(mapping));
    }

    @PostMapping("/migrate/prompt-catalog")
    /**
     * 全字段写入或更新提示词目录记录。
     */
    @Operation(summary = "写入提示词目录", description = "按全字段方式写入或更新 mcp_prompt_catalog 记录")
    public ResponseEntity<McpPromptCatalog> upsertPromptCatalog(@RequestBody McpPromptCatalog promptCatalog) {
        return ResponseEntity.ok(mcpService.upsertPromptCatalog(promptCatalog));
    }

    @PostMapping("/migrate/resource-catalog")
    /**
     * 全字段写入或更新资源目录记录。
     */
    @Operation(summary = "写入资源目录", description = "按全字段方式写入或更新 mcp_resource_catalog 记录")
    public ResponseEntity<McpResourceCatalog> upsertResourceCatalog(@RequestBody McpResourceCatalog resourceCatalog) {
        return ResponseEntity.ok(mcpService.upsertResourceCatalog(resourceCatalog));
    }

    @PostMapping("/migrate/workflow-template")
    /**
     * 全字段写入或更新工作流模板记录。
     */
    @Operation(summary = "写入工作流模板", description = "按全字段方式写入或更新 workflow_template 记录")
    public ResponseEntity<WorkflowTemplate> upsertWorkflowTemplate(@RequestBody WorkflowTemplate workflowTemplate) {
        return ResponseEntity.ok(mcpService.upsertWorkflowTemplate(workflowTemplate));
    }

    /**
     * 统一构造退役接口响应，明确返回 410 状态和替代路径说明。
     */
    private ResponseEntity<Map<String, Object>> legacyGone(String message) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "code", HttpStatus.GONE.value(),
                "message", message
        ));
    }
}
