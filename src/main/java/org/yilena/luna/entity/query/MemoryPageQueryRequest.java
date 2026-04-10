package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "历史内存分页查询参数")
/**
 * 历史内存分页查询请求对象，负责承接旧版内存后台查询所需的过滤条件。
 */
public class MemoryPageQueryRequest {

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

    @Schema(description = "会话 ID 精确查询条件")
    /**
     * 会话 ID 过滤条件。
     */
    private String sessionId;

    @Schema(description = "记忆类型，取值示例：FACT / PREFERENCE / SUMMARY / REFLECTION")
    /**
     * 记忆类型过滤条件。
     */
    private String memoryType;

    @Schema(description = "内容模糊查询条件")
    /**
     * 记忆内容模糊匹配条件。
     */
    private String content;

    @Schema(description = "最小权重")
    /**
     * 记忆权重下限过滤条件。
     */
    private Integer minWeight;

    @Schema(description = "最大权重")
    /**
     * 记忆权重上限过滤条件。
     */
    private Integer maxWeight;

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
