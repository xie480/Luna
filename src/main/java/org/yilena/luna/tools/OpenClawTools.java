package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 兼容占位类（已废弃）
 *
 * 说明：
 * OpenClaw 工具已按领域拆分为：
 * - PlanBlueprintTools
 * - PlanNodeTools
 * - PlanEventTools
 * - PlanCheckpointTools
 * - PlanReportTools
 * - ExecutionLockTools
 * - CodeOpsTools
 * - DesktopTools
 *
 * 当前类仅保留为空 Bean，避免旧代码注入时报错。
 * 不再承载任何 Tool 方法，防止与新实现重复注册或行为冲突。
 */
@Deprecated
@Component
public class OpenClawTools extends BaseTool {

    public OpenClawTools(ObjectMapper objectMapper) {
        super(objectMapper);
    }
}
