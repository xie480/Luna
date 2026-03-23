package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "阶段运行请求")
public class PlanPhaseRunRequest {

    @Schema(description = "计划ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "plan-123456")
    private String planId;

    @Schema(description = "阶段ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "phase-1")
    private String phaseId;
}
