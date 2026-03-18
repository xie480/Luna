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

/**
 * MCP Server 接口
 * 提供工具/技能的註冊與查詢
 */
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@Tag(name = "MCP 資源管理", description = "提供 MCP 工具(Tool)與技能(Skill)的註冊、查詢與檢索接口")
public class McpController {

    private final McpService mcpService;

    @PostMapping("/tools")
    @Operation(summary = "註冊原子工具", description = "註冊一個新的無狀態、同步執行的原子工具 (Tool)")
    public ResponseEntity<McpTool> registerTool(@RequestBody McpTool tool) {
        return ResponseEntity.ok(mcpService.registerTool(tool));
    }

    @PutMapping("/tools")
    @Operation(summary = "更新原子工具", description = "更新已存在的原子工具信息")
    public ResponseEntity<McpTool> updateTool(@RequestBody McpTool tool) {
        return ResponseEntity.ok(mcpService.updateTool(tool));
    }

    @DeleteMapping("/tools/{id}")
    @Operation(summary = "刪除原子工具", description = "根據 ID 刪除原子工具")
    public ResponseEntity<Void> deleteTool(@PathVariable String id) {
        mcpService.deleteTool(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/skills")
    @Operation(summary = "註冊複合技能", description = "註冊一個新的支持異步、審批、權限控制的複合技能 (Skill)")
    public ResponseEntity<McpSkill> registerSkill(@RequestBody McpSkill skill) {
        return ResponseEntity.ok(mcpService.registerSkill(skill));
    }

    @PutMapping("/skills")
    @Operation(summary = "更新複合技能", description = "更新已存在的複合技能信息")
    public ResponseEntity<McpSkill> updateSkill(@RequestBody McpSkill skill) {
        return ResponseEntity.ok(mcpService.updateSkill(skill));
    }

    @DeleteMapping("/skills/{id}")
    @Operation(summary = "刪除複合技能", description = "根據 ID 刪除複合技能")
    public ResponseEntity<Void> deleteSkill(@PathVariable String id) {
        mcpService.deleteSkill(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/resources")
    @Operation(summary = "獲取所有資源", description = "獲取系統中註冊的所有工具和技能列表")
    public ResponseEntity<List<Resource>> listAll() {
        return ResponseEntity.ok(mcpService.listAll());
    }

    @GetMapping("/resources/{id}")
    @Operation(summary = "根據 ID 獲取資源詳情", description = "根據資源的唯一標識 ID 獲取工具或技能的詳細信息")
    public ResponseEntity<Resource> getById(@PathVariable String id) {
        return ResponseEntity.ok(mcpService.getResourceById(id));
    }

    @PostMapping("/search")
    @Operation(summary = "語義搜索資源", description = "根據用戶輸入的自然語言 Query，通過向量檢索 (PGVector) 匹配最相關的工具和技能")
    public ResponseEntity<List<Resource>> search(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        return ResponseEntity.ok(mcpService.searchResources(query));
    }
}
