package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户偏好分页查询参数")
public class UserPreferencePageQueryRequest {

    @Schema(description = "页码，从1开始", example = "1")
    private Long pageNo = 1L;

    @Schema(description = "每页大小，最大200", example = "10")
    private Long pageSize = 10L;

    @Schema(description = "偏好键模糊查询")
    private String prefKey;

    @Schema(description = "偏好值模糊查询")
    private String prefValue;

    @Schema(description = "描述模糊查询")
    private String description;

    @Schema(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss")
    private String startTime;

    @Schema(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss")
    private String endTime;
}
