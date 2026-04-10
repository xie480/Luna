package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "计划阶段运行请求")
/**
 * 计划阶段运行请求对象，负责承接执行单个计划阶段所需的标识参数。
 */
public class PlanPhaseRunRequest {

    @Schema(description = "计划 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "plan-123456")
    /**
     * 目标计划的唯一标识。
     */
    private String planId;

    @Schema(description = "阶段 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "phase-1")
    /**
     * 待执行阶段的唯一标识。
     */
    private String phaseId;
}
