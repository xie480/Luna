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
@Tag(name = "MCP API", description = "Catalog and protocol endpoints")
/**
 * McpController ??
 */
public class McpController {

    private final McpService mcpService;

    // ===== Legacy CRUD API (retired) =====

    @PostMapping("/tools")
    @Operation(summary = "Register tool (legacy, retired)")
    public ResponseEntity<Map<String, Object>> registerTool() {
        return legacyGone("legacy /mcp/tools write API retired, use /mcp/migrate/tool-catalog and capability_registry path");
    }

    @PutMapping("/tools")
    @Operation(summary = "Update tool (legacy, retired)")
    public ResponseEntity<Map<String, Object>> updateTool() {
        return legacyGone("legacy /mcp/tools write API retired, use /mcp/migrate/tool-catalog and capability_registry path");
    }

    @DeleteMapping("/tools/{id}")
    @Operation(summary = "Delete tool (legacy, retired)")
    public ResponseEntity<Map<String, Object>> deleteTool(@PathVariable Long id) {
        return legacyGone("legacy /mcp/tools write API retired, use /mcp/migrate/tool-catalog and capability_registry path");
    }

    @PostMapping("/skills")
    @Operation(summary = "Register skill (legacy, retired)")
    public ResponseEntity<Map<String, Object>> registerSkill() {
        return legacyGone("legacy /mcp/skills write API retired, use /mcp/migrate/workflow-template or /mcp/migrate/prompt-catalog");
    }

    @PutMapping("/skills")
    @Operation(summary = "Update skill (legacy, retired)")
    public ResponseEntity<Map<String, Object>> updateSkill() {
        return legacyGone("legacy /mcp/skills write API retired, use /mcp/migrate/workflow-template or /mcp/migrate/prompt-catalog");
    }

    @DeleteMapping("/skills/{id}")
    @Operation(summary = "Delete skill (legacy, retired)")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable Long id) {
        return legacyGone("legacy /mcp/skills write API retired, use /mcp/migrate/workflow-template or /mcp/migrate/prompt-catalog");
    }

    @GetMapping("/resources")
    @Operation(summary = "List all capabilities")
    public ResponseEntity<List<Resource>> listAll() {
        return ResponseEntity.ok(mcpService.listAll());
    }

    @GetMapping("/resources/{id}")
    @Operation(summary = "Get capability by id")
    public ResponseEntity<Resource> getById(@PathVariable Long id) {
        return ResponseEntity.ok(mcpService.getResourceById(id));
    }

    @PostMapping("/search")
    @Operation(summary = "Search capabilities")
    public ResponseEntity<List<Resource>> search(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(mcpService.searchResources(body.get("query")));
    }

    // ===== MCP protocol style endpoints =====

    @GetMapping("/tools/list")
    @Operation(summary = "MCP tools/list")
    public ResponseEntity<?> listTools(@RequestParam(required = false) String serverCode) {
        return ResponseEntity.ok(mcpService.listTools(serverCode));
    }

    @PostMapping("/tools/call")
    @Operation(summary = "MCP tools/call")
    public ResponseEntity<?> callTool(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(mcpService.callTool(
                body.get("serverCode"),
                body.get("toolName"),
                body.get("argumentsJson")
        ));
    }

    @GetMapping("/prompts/list")
    @Operation(summary = "MCP prompts/list")
    public ResponseEntity<?> listPrompts(@RequestParam(required = false) String serverCode) {
        return ResponseEntity.ok(mcpService.listPrompts(serverCode));
    }

    @PostMapping("/prompts/get")
    @Operation(summary = "MCP prompts/get")
    public ResponseEntity<?> getPrompt(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(mcpService.getPrompt(
                body.get("serverCode"),
                body.get("promptName"),
                body.get("argumentsJson")
        ));
    }

    @GetMapping("/resources/list")
    @Operation(summary = "MCP resources/list")
    public ResponseEntity<?> listResources(@RequestParam(required = false) String serverCode) {
        return ResponseEntity.ok(mcpService.listResources(serverCode));
    }

    @PostMapping("/resources/read")
    @Operation(summary = "MCP resources/read")
    public ResponseEntity<?> readResource(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(mcpService.readResource(
                body.get("serverCode"),
                body.get("resourceUri")
        ));
    }

    @PostMapping("/catalog/sync")
    @Operation(summary = "Sync json/tool and json/skill into catalog tables")
    public ResponseEntity<?> syncCatalogFromJson() {
        return ResponseEntity.ok(mcpService.syncCatalogFromJson());
    }

    // ===== Migration upsert endpoints (full-field) =====

    @PostMapping("/migrate/server-registry")
    @Operation(summary = "Upsert mcp_server_registry (full-field)")
    public ResponseEntity<McpServerRegistry> upsertServerRegistry(@RequestBody McpServerRegistry registry) {
        return ResponseEntity.ok(mcpService.upsertServerRegistry(registry));
    }

    @PostMapping("/migrate/tool-catalog")
    @Operation(summary = "Upsert mcp_tool_catalog (full-field)")
    public ResponseEntity<McpToolCatalog> upsertToolCatalog(@RequestBody McpToolCatalog toolCatalog) {
        return ResponseEntity.ok(mcpService.upsertToolCatalog(toolCatalog));
    }

    @PostMapping("/migrate/tool-impl-mapping")
    @Operation(summary = "Upsert mcp_tool_impl_mapping (full-field)")
    public ResponseEntity<McpToolImplMapping> upsertToolImplMapping(@RequestBody McpToolImplMapping mapping) {
        return ResponseEntity.ok(mcpService.upsertToolImplMapping(mapping));
    }

    @PostMapping("/migrate/prompt-catalog")
    @Operation(summary = "Upsert mcp_prompt_catalog (full-field)")
    public ResponseEntity<McpPromptCatalog> upsertPromptCatalog(@RequestBody McpPromptCatalog promptCatalog) {
        return ResponseEntity.ok(mcpService.upsertPromptCatalog(promptCatalog));
    }

    @PostMapping("/migrate/resource-catalog")
    @Operation(summary = "Upsert mcp_resource_catalog (full-field)")
    public ResponseEntity<McpResourceCatalog> upsertResourceCatalog(@RequestBody McpResourceCatalog resourceCatalog) {
        return ResponseEntity.ok(mcpService.upsertResourceCatalog(resourceCatalog));
    }

    @PostMapping("/migrate/workflow-template")
    @Operation(summary = "Upsert workflow_template (full-field)")
    public ResponseEntity<WorkflowTemplate> upsertWorkflowTemplate(@RequestBody WorkflowTemplate workflowTemplate) {
        return ResponseEntity.ok(mcpService.upsertWorkflowTemplate(workflowTemplate));
    }

    private ResponseEntity<Map<String, Object>> legacyGone(String message) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "code", HttpStatus.GONE.value(),
                "message", message
        ));
    }
}
