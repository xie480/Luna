package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yilena.luna.service.ApprovalService;

import java.util.Map;

@RestController
@RequestMapping("/mcp/skills")
@RequiredArgsConstructor
@Tag(name = "MCP 審批管理", description = "處理敏感操作的人工審批回調")
public class ApprovalController {

    private final ApprovalService approvalService;

    @PostMapping("/approval")
    @Operation(summary = "提交審批結果", description = "前端用戶點擊同意或拒絕後調用此接口")
    public ResponseEntity<String> submitApproval(@RequestBody Map<String, Object> body) {
        String taskId = (String) body.get("taskId");
        Boolean approved = (Boolean) body.get("approved");
        
        if (taskId == null || approved == null) {
            return ResponseEntity.badRequest().body("taskId and approved are required");
        }

        // 處理審批並獲取執行結果
        // 注意：在完整架構中，這裡可能還需要觸發 AgentService 恢復對話
        String result = approvalService.processApproval(taskId, approved);
        
        return ResponseEntity.ok(result);
    }
}
