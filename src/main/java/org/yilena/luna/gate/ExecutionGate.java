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
 * 执行权限闸门，负责在工具真正执行前校验调用主体、敏感级别和审批要求。
 */
@Slf4j
@Component
public class ExecutionGate {

    /**
     * 具备高权限能力的主体集合，用于放行高敏感或需审批工具。
     */
    private static final Set<String> PRIVILEGED_PRINCIPALS = loadPrivilegedPrincipals();

    /**
     * 校验当前资源是否允许执行，不满足策略时直接抛出安全异常。
     */
    public void check(Resource resource) {
        log.info("正在进行安全检查: {}", resource == null ? "" : resource.getName());
        String principal = normalizePrincipal(AuthContextHolder.getPrincipalKey());
        Sensitivity sensitivity = resource == null || resource.getSensitivity() == null
                ? Sensitivity.LOW
                : resource.getSensitivity();
        boolean approvalRequired = resource != null && Boolean.TRUE.equals(resource.getRequiresApproval());

        /**
         * 先校验受保护能力是否具备明确调用主体，避免匿名身份调用高风险工具。
         */
        if ((approvalRequired || Sensitivity.MEDIUM.equals(sensitivity) || Sensitivity.HIGH.equals(sensitivity))
                && principal.isBlank()) {
            throw new IllegalStateException("SECURITY_POLICY_VIOLATION: missing principal for protected capability");
        }

        /**
         * 高敏感工具要求调用主体在高权限名单中，否则直接阻断执行。
         */
        if (Sensitivity.HIGH.equals(sensitivity)) {
            if (!isPrivilegedPrincipal(principal)) {
                throw new SecurityException("SECURITY_POLICY_VIOLATION: HIGH sensitivity requires privileged principal");
            }
            log.info("检测到高敏感工具 {}，后续将允许进入审批流程", resource.getName());
        }

        /**
         * 明确要求审批的工具同样需要高权限主体，避免普通身份绕过治理流程。
         */
        if (approvalRequired) {
            if (!isPrivilegedPrincipal(principal)) {
                throw new SecurityException("SECURITY_POLICY_VIOLATION: approval-required capability requires privileged principal");
            }
            log.info("工具 [{}] 需要人工审批，将进入审批流程", resource.getName());
        }
    }

    /**
     * 从环境变量或系统属性加载高权限主体名单，未配置时默认包含 admin。
     */
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

    /**
     * 规范化主体列表字符串，空值回退为空串。
     */
    private static String normalizeList(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    /**
     * 判断当前主体是否属于高权限名单，兼容带命名空间前缀的主体标识。
     */
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

    /**
     * 规范化主体标识，统一转为小写便于权限判断。
     */
    private String normalizePrincipal(String principal) {
        if (principal == null) {
            return "";
        }
        return principal.trim().toLowerCase(Locale.ROOT);
    }
}
