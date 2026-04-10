package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "知识库分页查询参数")
/**
 * 知识库分页查询请求对象，负责承接知识片段后台检索所需的筛选条件。
 */
public class KnowledgeBasePageQueryRequest {

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

    @Schema(description = "标题模糊查询条件")
    /**
     * 知识标题模糊匹配条件。
     */
    private String title;

    @Schema(description = "内容模糊查询条件")
    /**
     * 知识内容模糊匹配条件。
     */
    private String content;

    @Schema(description = "来源类型，取值示例：FILE / WEB_SEARCH / MANUAL_INPUT")
    /**
     * 知识来源类型过滤条件。
     */
    private String sourceType;

    @Schema(description = "来源路径模糊查询条件")
    /**
     * 知识来源路径过滤条件。
     */
    private String sourcePath;

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
