package org.yilena.luna.entity.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "知识库分页查询参数")
/**
 * KnowledgeBasePageQueryRequest ??
 */
public class KnowledgeBasePageQueryRequest {

    @Schema(description = "页码，从1开始", example = "1")
    private Long pageNo = 1L;

    @Schema(description = "每页大小，最大200", example = "10")
    private Long pageSize = 10L;

    @Schema(description = "标题模糊查询")
    private String title;

    @Schema(description = "内容模糊查询")
    private String content;

    @Schema(description = "来源类型：FILE / WEB_SEARCH / MANUAL_INPUT")
    private String sourceType;

    @Schema(description = "来源路径模糊查询")
    private String sourcePath;

    @Schema(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss")
    private String startTime;

    @Schema(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss")
    private String endTime;
}
