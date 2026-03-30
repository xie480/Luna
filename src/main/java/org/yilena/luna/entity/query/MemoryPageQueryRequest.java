package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "长期记忆分页查询参数")
/**
 * MemoryPageQueryRequest ??
 */
public class MemoryPageQueryRequest {

    @Schema(description = "页码，从1开始", example = "1")
    private Long pageNo = 1L;

    @Schema(description = "每页大小，最大200", example = "10")
    private Long pageSize = 10L;

    @Schema(description = "会话ID精确查询")
    private String sessionId;

    @Schema(description = "记忆类型：FACT / PREFERENCE / SUMMARY / REFLECTION")
    private String memoryType;

    @Schema(description = "内容模糊查询")
    private String content;

    @Schema(description = "最小权重")
    private Integer minWeight;

    @Schema(description = "最大权重")
    private Integer maxWeight;

    @Schema(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss")
    private String startTime;

    @Schema(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss")
    private String endTime;
}
