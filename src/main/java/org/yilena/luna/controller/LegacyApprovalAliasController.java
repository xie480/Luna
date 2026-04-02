package org.yilena.luna.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class LegacyApprovalAliasController {

    private final ApprovalController approvalController;

    @Value("${luna.mcp.compat.skill-endpoint-enabled:false}")
    private boolean skillEndpointEnabled;

    @PostMapping("/mcp/workflows/approval")
    public ResponseEntity<Object> submitApprovalWorkflow(@RequestBody Map<String, Object> body) {
        return approvalController.submitApproval(body);
    }

    @PostMapping("/mcp/skills/approval")
    public ResponseEntity<Object> submitApprovalLegacyAlias(@RequestBody Map<String, Object> body) {
        if (!skillEndpointEnabled) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "code", 410,
                    "errorCode", "WORKFLOW_COMPAT_SKILL_ENDPOINT_RETIRED",
                    "message", "Endpoint retired. Use /mcp/workflows/approval or /mcp/tools/approval."
            ));
        }
        return approvalController.submitApproval(body);
    }
}
