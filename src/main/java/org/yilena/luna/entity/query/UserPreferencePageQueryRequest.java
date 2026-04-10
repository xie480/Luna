package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户偏好分页查询参数")
/**
 * 用户偏好分页查询请求对象，负责承接旧版偏好后台查询所需的过滤条件。
 */
public class UserPreferencePageQueryRequest {

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

    @Schema(description = "偏好键模糊查询条件")
    /**
     * 偏好键模糊匹配条件。
     */
    private String prefKey;

    @Schema(description = "偏好值模糊查询条件")
    /**
     * 偏好值模糊匹配条件。
     */
    private String prefValue;

    @Schema(description = "描述模糊查询条件")
    /**
     * 偏好描述模糊匹配条件。
     */
    private String description;

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
