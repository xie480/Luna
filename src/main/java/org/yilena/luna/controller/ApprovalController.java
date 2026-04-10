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
@Tag(name = "MCP 审批接口", description = "处理敏感工具调用的人工审批结果回传")
/**
 * MCP 审批控制器，负责接收前端提交的审批结果并继续后续会话流程。
 */
public class ApprovalController {

    /**
     * 审批服务，负责审批任务流转和审批后的续聊处理。
     */
    private final ApprovalService approvalService;
    /**
     * JSON 处理器，用于统一解析审批结果返回值。
     */
    private final ObjectMapper objectMapper;

    @PostMapping("/approval")
    /**
     * 提交人工审批结果。
     *
     * 该接口会校验任务编号和审批结论，并在审批通过或拒绝后继续推进对应的工具调用或会话续处理。
     */
    @Operation(summary = "提交审批结果", description = "当前端用户对敏感工具调用做出同意或拒绝后，通过该接口回传审批结论")
    public ResponseEntity<Object> submitApproval(@RequestBody Map<String, Object> body) {
        /**
         * 从请求体中解析审批任务编号和审批结果，并进行基础参数校验。
         */
        String taskId = body.get(JsonFieldConstants.TASK_ID) == null ? null : String.valueOf(body.get(JsonFieldConstants.TASK_ID)).trim();
        Boolean approved = parseApproved(body.get(JsonFieldConstants.APPROVED));

        if (taskId == null || taskId.isBlank() || approved == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    JsonFieldConstants.STATUS, ResultStatusConstants.ERROR,
                    JsonFieldConstants.MESSAGE, MessageConstants.APPROVAL_PARAMS_REQUIRED
            ));
        }

        /**
         * 交由审批服务处理审批流转，并尽量将结果包装为结构化 JSON 返回给前端。
         */
        String result = approvalService.processApproval(taskId, approved);
        try {
            JsonNode node = objectMapper.readTree(result);
            return ResponseEntity.ok(node);
        } catch (Exception ignore) {
            return ResponseEntity.ok(Map.of(JsonFieldConstants.RAW, result));
        }
    }

    /**
     * 兼容布尔、数值和文本多种审批结果表达方式，统一转换为布尔值。
     */
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
