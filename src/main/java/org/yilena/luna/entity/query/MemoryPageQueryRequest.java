package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "长期记忆分页查询参数")
/**
 * MemoryPageQueryRequest ??
 */
public class MemoryPageQueryRequest {

    @Schema(description = "页码，从1开始", example = "1") // 声明注解
    private Long pageNo = 1L; // 声明成员字段

    @Schema(description = "每页大小，最大200", example = "10") // 声明注解
    private Long pageSize = 10L; // 声明成员字段

    @Schema(description = "会话ID精确查询") // 声明注解
    private String sessionId; // 声明成员字段

    @Schema(description = "记忆类型：FACT / PREFERENCE / SUMMARY / REFLECTION") // 声明注解
    private String memoryType; // 声明成员字段

    @Schema(description = "内容模糊查询") // 声明注解
    private String content; // 声明成员字段

    @Schema(description = "最小权重") // 声明注解
    private Integer minWeight; // 声明成员字段

    @Schema(description = "最大权重") // 声明注解
    private Integer maxWeight; // 声明成员字段

    @Schema(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss") // 声明注解
    private String startTime; // 声明成员字段

    @Schema(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss") // 声明注解
    private String endTime; // 声明成员字段
} // 结束当前代码块
