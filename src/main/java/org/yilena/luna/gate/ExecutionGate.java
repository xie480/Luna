package org.yilena.luna.gate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.Sensitivity;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 執行網關
 * 負責權限檢查和審批標記
 */
@Slf4j
@Component
public class ExecutionGate {

    private static final Set<String> PRIVILEGED_PRINCIPALS = loadPrivilegedPrincipals();

    /**
     * 檢查資源是否允許執行
     * @param resource 目標資源
     * @throws RuntimeException 如果權限不足
     */
    public void check(Resource resource) {
        log.info("正在進行安全檢查: {}", resource == null ? "" : resource.getName());
        String principal = normalizePrincipal(AuthContextHolder.getPrincipalKey());
        Sensitivity sensitivity = resource == null || resource.getSensitivity() == null
                ? Sensitivity.LOW
                : resource.getSensitivity();
        boolean approvalRequired = resource != null && Boolean.TRUE.equals(resource.getRequiresApproval());

        // 0. 基础执行身份校验（治理硬策略）
        if ((approvalRequired || Sensitivity.MEDIUM.equals(sensitivity) || Sensitivity.HIGH.equals(sensitivity))
                && principal.isBlank()) {
            throw new IllegalStateException("SECURITY_POLICY_VIOLATION: missing principal for protected capability");
        }

        // 1. 敏感度檢查
        if (Sensitivity.HIGH.equals(sensitivity)) {
            if (!isPrivilegedPrincipal(principal)) {
                throw new SecurityException("SECURITY_POLICY_VIOLATION: HIGH sensitivity requires privileged principal");
            }
            log.info("檢測到高敏感度工具: {}，後續將觸發審批流程", resource.getName());
        }

        // 2. 審批標記檢查
        if (approvalRequired) {
            if (!isPrivilegedPrincipal(principal)) {
                throw new SecurityException("SECURITY_POLICY_VIOLATION: approval-required capability requires privileged principal");
            }
            log.info("工具 [{}] 需要人工審批，將進入審批流程", resource.getName());
        }
    }

    private static Set<String> loadPrivilegedPrincipals() {
        String fromEnv = System.getenv("LUNA_MCP_PRIVILEGED_PRINCIPALS");
        String fromProp = System.getProperty("luna.mcp.privileged-principals");
        String merged = normalizeList(fromEnv);
        if (merged.isBlank()) {
            merged = normalizeList(fromProp);
        }
        if (merged.isBlank()) {
            merged = "admin";
        }
        return Arrays.stream(merged.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static String normalizeList(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private boolean isPrivilegedPrincipal(String principal) {
        if (principal == null || principal.isBlank()) {
            return false;
        }
        if (PRIVILEGED_PRINCIPALS.contains(principal)) {
            return true;
        }
        int idx = principal.indexOf(':');
        if (idx > 0 && idx + 1 < principal.length()) {
            return PRIVILEGED_PRINCIPALS.contains(principal.substring(idx + 1));
        }
        return false;
    }

    private String normalizePrincipal(String principal) {
        if (principal == null) {
            return "";
        }
        return principal.trim().toLowerCase(Locale.ROOT);
    }
}
