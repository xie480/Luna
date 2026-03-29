package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.McpSkill;
import org.yilena.luna.entity.McpTool;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.service.McpService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@Tag(name = "MCP API", description = "Catalog and protocol endpoints")
public class McpController {

    private final McpService mcpService;

    // ===== Legacy CRUD API (compatibility) =====

    @PostMapping("/tools")
    @Operation(summary = "Register tool (legacy)")
    public ResponseEntity<McpTool> registerTool(@RequestBody McpTool tool) {
        return ResponseEntity.ok(mcpService.registerTool(tool));
    }

    @PutMapping("/tools")
    @Operation(summary = "Update tool (legacy)")
    public ResponseEntity<McpTool> updateTool(@RequestBody McpTool tool) {
        return ResponseEntity.ok(mcpService.updateTool(tool));
    }

    @DeleteMapping("/tools/{id}")
    @Operation(summary = "Delete tool (legacy)")
    public ResponseEntity<Void> deleteTool(@PathVariable Long id) {
        mcpService.deleteTool(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/skills")
    @Operation(summary = "Register skill (legacy)")
    public ResponseEntity<McpSkill> registerSkill(@RequestBody McpSkill skill) {
        return ResponseEntity.ok(mcpService.registerSkill(skill));
    }

    @PutMapping("/skills")
    @Operation(summary = "Update skill (legacy)")
    public ResponseEntity<McpSkill> updateSkill(@RequestBody McpSkill skill) {
        return ResponseEntity.ok(mcpService.updateSkill(skill));
    }

    @DeleteMapping("/skills/{id}")
    @Operation(summary = "Delete skill (legacy)")
    public ResponseEntity<Void> deleteSkill(@PathVariable Long id) {
        mcpService.deleteSkill(id);
        return ResponseEntity.ok().build();
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
}
