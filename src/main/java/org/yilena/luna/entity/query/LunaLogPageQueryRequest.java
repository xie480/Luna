package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "日志分页查询参数")
public class LunaLogPageQueryRequest {

    @Schema(description = "页码，从1开始", example = "1")
    private Long pageNo = 1L;

    @Schema(description = "每页大小，最大200", example = "10")
    private Long pageSize = 10L;

    @Schema(description = "日志类型：LUNA_OUTPUT / TOOL_CALL / ERROR / SELF_UPDATE / SYSTEM_EVENT / API_CALL")
    private String logType;

    @Schema(description = "模块模糊查询")
    private String module;

    @Schema(description = "动作模糊查询")
    private String action;

    @Schema(description = "内容模糊查询")
    private String content;

    @Schema(description = "traceId 精确查询")
    private String traceId;

    @Schema(description = "operatorId 精确查询")
    private String operatorId;

    @Schema(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss")
    private String startTime;

    @Schema(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss")
    private String endTime;
}
