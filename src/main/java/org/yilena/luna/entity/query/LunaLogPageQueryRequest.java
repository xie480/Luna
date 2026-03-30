package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "日志分页查询参数")
/**
 * LunaLogPageQueryRequest ??
 */
public class LunaLogPageQueryRequest {

    @Schema(description = "页码，从1开始", example = "1") // 声明注解
    private Long pageNo = 1L; // 声明成员字段

    @Schema(description = "每页大小，最大200", example = "10") // 声明注解
    private Long pageSize = 10L; // 声明成员字段

    @Schema(description = "日志类型：LUNA_OUTPUT / TOOL_CALL / ERROR / SELF_UPDATE / SYSTEM_EVENT / API_CALL") // 声明注解
    private String logType; // 声明成员字段

    @Schema(description = "模块模糊查询") // 声明注解
    private String module; // 声明成员字段

    @Schema(description = "动作模糊查询") // 声明注解
    private String action; // 声明成员字段

    @Schema(description = "内容模糊查询") // 声明注解
    private String content; // 声明成员字段

    @Schema(description = "traceId 精确查询") // 声明注解
    private String traceId; // 声明成员字段

    @Schema(description = "operatorId 精确查询") // 声明注解
    private String operatorId; // 声明成员字段

    @Schema(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss") // 声明注解
    private String startTime; // 声明成员字段

    @Schema(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss") // 声明注解
    private String endTime; // 声明成员字段
} // 结束当前代码块
