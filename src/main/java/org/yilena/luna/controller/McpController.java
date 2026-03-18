package org.yilena.luna.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
public class McpController {

    private final McpService mcpService;

    @PostMapping("/resources")
    public ResponseEntity<Resource> register(@RequestBody Resource resource) {
        return ResponseEntity.ok(mcpService.registerResource(resource));
    }

    @GetMapping("/resources")
    public ResponseEntity<List<Resource>> list() {
        return ResponseEntity.ok(mcpService.list());
    }

    @GetMapping("/resources/{id}")
    public ResponseEntity<Resource> getById(@PathVariable String id) {
        return ResponseEntity.ok(mcpService.getById(id));
    }

    @PostMapping("/search")
    public ResponseEntity<List<Resource>> search(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        return ResponseEntity.ok(mcpService.searchResources(query));
    }
}
