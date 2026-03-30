package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "阶段运行请求")
/**
 * PlanPhaseRunRequest ??
 */
public class PlanPhaseRunRequest {

    @Schema(description = "计划ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "plan-123456") // 声明注解
    private String planId; // 声明成员字段

    @Schema(description = "阶段ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "phase-1") // 声明注解
    private String phaseId; // 声明成员字段
} // 结束当前代码块
