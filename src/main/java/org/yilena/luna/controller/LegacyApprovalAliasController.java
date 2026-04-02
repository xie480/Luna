package org.yilena.luna.controller;

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
public class LegacyApprovalAliasController {

    private final ApprovalController approvalController;

    @PostMapping("/mcp/skills/approval")
    public ResponseEntity<Object> submitApprovalLegacyAlias(@RequestBody Map<String, Object> body) {
        return approvalController.submitApproval(body);
    }
}
