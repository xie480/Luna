package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "日志分页查询参数")
/**
 * 日志分页查询请求对象，负责承接日志后台查询所需的分页和筛选条件。
 */
public class LunaLogPageQueryRequest {

    @Schema(description = "页码，从 1 开始", example = "1")
    /**
     * 当前请求页码，默认第一页。
     */
    private Long pageNo = 1L;

    @Schema(description = "每页大小，最大 200", example = "10")
    /**
     * 当前页大小，默认 10 条。
     */
    private Long pageSize = 10L;

    @Schema(description = "日志类型，取值示例：LUNA_OUTPUT / TOOL_CALL / ERROR / SELF_UPDATE / SYSTEM_EVENT / API_CALL")
    /**
     * 日志类型过滤条件。
     */
    private String logType;

    @Schema(description = "模块模糊查询条件")
    /**
     * 模块名称模糊匹配条件。
     */
    private String module;

    @Schema(description = "动作模糊查询条件")
    /**
     * 动作名称模糊匹配条件。
     */
    private String action;

    @Schema(description = "内容模糊查询条件")
    /**
     * 日志内容模糊匹配条件。
     */
    private String content;

    @Schema(description = "traceId 精确查询条件")
    /**
     * 链路追踪 ID 精确匹配条件。
     */
    private String traceId;

    @Schema(description = "operatorId 精确查询条件")
    /**
     * 操作人 ID 精确匹配条件。
     */
    private String operatorId;

    @Schema(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss")
    /**
     * 查询时间范围起点。
     */
    private String startTime;

    @Schema(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss")
    /**
     * 查询时间范围终点。
     */
    private String endTime;
}
