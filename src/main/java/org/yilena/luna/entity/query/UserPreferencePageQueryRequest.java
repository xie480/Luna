package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户偏好分页查询参数")
/**
 * UserPreferencePageQueryRequest ??
 */
public class UserPreferencePageQueryRequest {

    @Schema(description = "页码，从1开始", example = "1") // 声明注解
    private Long pageNo = 1L; // 声明成员字段

    @Schema(description = "每页大小，最大200", example = "10") // 声明注解
    private Long pageSize = 10L; // 声明成员字段

    @Schema(description = "偏好键模糊查询") // 声明注解
    private String prefKey; // 声明成员字段

    @Schema(description = "偏好值模糊查询") // 声明注解
    private String prefValue; // 声明成员字段

    @Schema(description = "描述模糊查询") // 声明注解
    private String description; // 声明成员字段

    @Schema(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss") // 声明注解
    private String startTime; // 声明成员字段

    @Schema(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss") // 声明注解
    private String endTime; // 声明成员字段
} // 结束当前代码块
