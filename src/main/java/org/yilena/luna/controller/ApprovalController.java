package org.yilena.luna.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yilena.luna.constants.BooleanTextConstants;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.MessageConstants;
import org.yilena.luna.constants.ResultStatusConstants;
import org.yilena.luna.service.ApprovalService;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/mcp/tools")
@RequiredArgsConstructor
@Tag(name = "MCP 审批管理", description = "处理敏感操作的人工审批回调")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;

    @PostMapping("/approval")
    @Operation(summary = "提交审批结果", description = "前端用户点击同意或拒绝后调用此接口")
    public ResponseEntity<Object> submitApproval(@RequestBody Map<String, Object> body) {
        String taskId = body.get(JsonFieldConstants.TASK_ID) == null ? null : String.valueOf(body.get(JsonFieldConstants.TASK_ID)).trim();
        Boolean approved = parseApproved(body.get(JsonFieldConstants.APPROVED));

        if (taskId == null || taskId.isBlank() || approved == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    JsonFieldConstants.STATUS, ResultStatusConstants.ERROR,
                    JsonFieldConstants.MESSAGE, MessageConstants.APPROVAL_PARAMS_REQUIRED
            ));
        }

        String result = approvalService.processApproval(taskId, approved);
        try {
            JsonNode node = objectMapper.readTree(result);
            return ResponseEntity.ok(node);
        } catch (Exception ignore) {
            return ResponseEntity.ok(Map.of(JsonFieldConstants.RAW, result));
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
            if (value == Integer.parseInt(BooleanTextConstants.ONE)) {
                return true;
            }
            if (value == Integer.parseInt(BooleanTextConstants.ZERO)) {
                return false;
            }
            return null;
        }

        String text = String.valueOf(approvedObj).trim().toLowerCase(Locale.ROOT);
        if (BooleanTextConstants.TRUE.equals(text)
                || BooleanTextConstants.ONE.equals(text)
                || BooleanTextConstants.YES.equals(text)) {
            return true;
        }
        if (BooleanTextConstants.FALSE.equals(text)
                || BooleanTextConstants.ZERO.equals(text)
                || BooleanTextConstants.NO.equals(text)) {
            return false;
        }
        return null;
    }
}
