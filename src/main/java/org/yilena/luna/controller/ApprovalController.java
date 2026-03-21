package org.yilena.luna.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.service.ApprovalService;

import java.util.Map;

@RestController
@RequestMapping("/mcp/skills")
@RequiredArgsConstructor
@Tag(name = "MCP 審批管理", description = "處理敏感操作的人工審批回調")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;

    @PostMapping("/approval")
    @Operation(summary = "提交審批結果", description = "前端用戶點擊同意或拒絕後調用此接口")
    public ResponseEntity<Object> submitApproval(@RequestBody Map<String, Object> body) {
        String taskId = (String) body.get("taskId");
        Boolean approved = (Boolean) body.get("approved");

        if (taskId == null || approved == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "taskId and approved are required"
            ));
        }

        // 處理審批並獲取執行結果（字符串）
        String result = approvalService.processApproval(taskId, approved);

        // 優先按 JSON 返回，避免 text/plain 導致前端“無輸出感知”
        try {
            JsonNode node = objectMapper.readTree(result);
            return ResponseEntity.ok(node);
        } catch (Exception ignore) {
            return ResponseEntity.ok(Map.of("raw", result));
        }
    }
}
