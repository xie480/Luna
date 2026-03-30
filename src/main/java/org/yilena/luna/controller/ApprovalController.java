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

    private final ApprovalService approvalService; // 声明成员字段
    private final ObjectMapper objectMapper; // 声明成员字段

    @PostMapping("/approval") // 声明注解
    @Operation(summary = "提交审批结果", description = "前端用户点击同意或拒绝后调用此接口") // 声明注解
    public ResponseEntity<Object> submitApproval(@RequestBody Map<String, Object> body) { // 定义方法签名
        String taskId = body.get("taskId") == null ? null : String.valueOf(body.get("taskId")).trim(); // 执行赋值操作
        Boolean approved = parseApproved(body.get("approved")); // 执行赋值操作

        if (taskId == null || taskId.isBlank() || approved == null) { // 进行条件判断
            return ResponseEntity.badRequest().body(Map.of( // 返回处理结果
                    "status", "error", // 执行当前逻辑
                    "message", "taskId and approved are required" // 执行当前逻辑
            )); // 执行语句逻辑
        } // 结束当前代码块

        String result = approvalService.processApproval(taskId, approved); // 执行赋值操作

        try { // 尝试执行核心逻辑
            JsonNode node = objectMapper.readTree(result); // 执行赋值操作
            return ResponseEntity.ok(node); // 返回处理结果
        } catch (Exception ignore) { // 开始新的代码块
            return ResponseEntity.ok(Map.of("raw", result)); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private Boolean parseApproved(Object approvedObj) { // 定义方法签名
        if (approvedObj == null) { // 进行条件判断
            return null; // 返回处理结果
        } // 结束当前代码块
        if (approvedObj instanceof Boolean bool) { // 进行条件判断
            return bool; // 返回处理结果
        } // 结束当前代码块
        if (approvedObj instanceof Number number) { // 进行条件判断
            int value = number.intValue(); // 执行赋值操作
            if (value == 1) { // 进行条件判断
                return true; // 返回处理结果
            } // 结束当前代码块
            if (value == 0) { // 进行条件判断
                return false; // 返回处理结果
            } // 结束当前代码块
            return null; // 返回处理结果
        } // 结束当前代码块

        String text = String.valueOf(approvedObj).trim(); // 执行赋值操作
        if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text)) { // 进行条件判断
            return true; // 返回处理结果
        } // 结束当前代码块
        if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) { // 进行条件判断
            return false; // 返回处理结果
        } // 结束当前代码块
        return null; // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
