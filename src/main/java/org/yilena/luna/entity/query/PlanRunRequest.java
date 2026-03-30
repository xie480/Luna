package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "计划运行请求")
/**
 * PlanRunRequest ??
 */
public class PlanRunRequest {

    @Schema(description = "会话ID（可选，未传则优先使用JWT jti）", example = "session-123") // 声明注解
    private String sessionId; // 声明成员字段

    @Schema(description = "用户目标", requiredMode = Schema.RequiredMode.REQUIRED, example = "帮我整理今天的项目代码并生成报告") // 声明注解
    private String userGoal; // 声明成员字段
} // 结束当前代码块
