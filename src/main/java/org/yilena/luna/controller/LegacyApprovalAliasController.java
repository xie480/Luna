package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "luna.mcp.compat.skill-endpoint-enabled", havingValue = "true")
@Tag(name = "历史审批兼容接口", description = "兼容旧版技能审批路径，内部转发到新版审批接口")
/**
 * 历史审批别名控制器，用于兼容旧版技能审批接口地址。
 */
public class LegacyApprovalAliasController {

    /**
     * 新版审批控制器，旧接口请求会直接委托到该控制器处理。
     */
    private final ApprovalController approvalController;

    @PostMapping("/mcp/skills/approval")
    /**
     * 兼容旧版审批路径，避免历史前端在迁移期间调用失败。
     */
    @Operation(summary = "提交旧版审批结果", description = "兼容旧版技能审批接口地址，内部转发到新版审批处理流程")
    public ResponseEntity<Object> submitApprovalLegacyAlias(@RequestBody Map<String, Object> body) {
        return approvalController.submitApproval(body);
    }
}
