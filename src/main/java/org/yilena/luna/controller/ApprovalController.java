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
@Tag(name = "MCP 审批管理", description = "处理敏感操作的人工审批回调")
/**
 * ApprovalController ??
 */
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;

    @PostMapping("/approval")
    @Operation(summary = "提交审批结果", description = "前端用户点击同意或拒绝后调用此接口")
    public ResponseEntity<Object> submitApproval(@RequestBody Map<String, Object> body) {
        // 统一提取并规整审批任务 ID。
        String taskId = body.get("taskId") == null ? null : String.valueOf(body.get("taskId")).trim();
        // 兼容布尔值、数字和字符串等多种 approved 入参类型。
        Boolean approved = parseApproved(body.get("approved"));

        // taskId/approved 缺失时直接返回 400，避免无效状态落库。
        if (taskId == null || taskId.isBlank() || approved == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "taskId and approved are required"
            ));
        }

        // 调用审批服务更新任务状态并触发后续流程。
        String result = approvalService.processApproval(taskId, approved);

        try {
            // 服务层返回 JSON 字符串时转换成结构化对象回传前端。
            JsonNode node = objectMapper.readTree(result);
            return ResponseEntity.ok(node);
        } catch (Exception ignore) {
            // 非 JSON 结果兜底按原文返回，避免响应丢失。
            return ResponseEntity.ok(Map.of("raw", result));
        }
    }

    private Boolean parseApproved(Object approvedObj) {
        if (approvedObj == null) {
            return null;
        }
        if (approvedObj instanceof Boolean bool) {
            return bool;
        }
        if (approvedObj instanceof Number number) {
            int value = number.intValue();
            if (value == 1) {
                return true;
            }
            if (value == 0) {
                return false;
            }
            return null;
        }

        String text = String.valueOf(approvedObj).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }
}
