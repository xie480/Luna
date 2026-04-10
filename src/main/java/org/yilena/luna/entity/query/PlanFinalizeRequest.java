package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "计划收尾请求")
/**
 * 计划收尾请求对象，负责承接生成最终报告时需要的计划标识。
 */
public class PlanFinalizeRequest {

    @Schema(description = "计划 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "plan-123456")
    /**
     * 需要做收尾并生成报告的计划标识。
     */
    private String planId;
}
