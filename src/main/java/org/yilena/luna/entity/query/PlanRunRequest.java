package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "计划运行请求")
/**
 * 计划运行请求对象，负责承接创建并执行计划时的输入参数。
 */
public class PlanRunRequest {

    @Schema(description = "会话 ID，可选；未传时优先使用登录态中的会话标识", example = "session-123")
    /**
     * 执行计划时归属的会话标识，用于串联上下文。
     */
    private String sessionId;

    @Schema(description = "用户目标", requiredMode = Schema.RequiredMode.REQUIRED, example = "帮我整理今天的项目代码并生成报告")
    /**
     * 用户希望计划完成的目标描述。
     */
    private String userGoal;
}
