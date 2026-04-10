package org.yilena.luna.controller.query;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.KnowledgeChunkRecord;
import org.yilena.luna.entity.query.KnowledgeBasePageQueryRequest;
import org.yilena.luna.entity.query.PagedResponse;
import org.yilena.luna.service.KnowledgeBaseService;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/luna/api/query/knowledge-base")
@RequiredArgsConstructor
@Tag(name = "知识库查询接口", description = "提供知识库片段的条件分页查询能力")
/**
 * 知识库查询控制器，负责按标题、内容、来源和时间范围分页检索知识库片段。
 */
public class KnowledgeBaseController extends BasePageQueryController {

    /**
     * 知识库服务，负责知识片段统计和分页查询。
     */
    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping({"", "/page"})
    /**
     * 按条件分页查询知识库内容。
     *
     * 该接口会统一规范分页参数、解析时间范围并构造过滤条件，最后以统一分页结构返回结果。
     */
    @Operation(summary = "分页查询知识库", description = "根据标题、内容、来源类型、来源路径和时间范围分页查询知识片段")
    public ResponseEntity<Object> page(@RequestBody(required = false) KnowledgeBasePageQueryRequest req) {
        try {
            /**
             * 请求体为空时补齐默认查询对象，确保分页参数和筛选条件可安全读取。
             */
            KnowledgeBasePageQueryRequest request = req == null ? new KnowledgeBasePageQueryRequest() : req;
            long pageNo = normalizePageNo(request.getPageNo());
            long pageSize = normalizePageSize(request.getPageSize());
            Page<KnowledgeChunkRecord> page = new Page<>(pageNo, pageSize);
            LocalDateTime startTime = hasText(request.getStartTime()) ? parseDateTime(request.getStartTime()) : null;
            LocalDateTime endTime = hasText(request.getEndTime()) ? parseDateTime(request.getEndTime()) : null;
            String sourceType = hasText(request.getSourceType()) ? request.getSourceType().trim().toUpperCase() : null;

            /**
             * 先统计符合条件的总数，再查询当前页数据，保持分页信息和结果数据一致。
             */
            Long total = knowledgeBaseService.countKnowledge(
                    trimToNull(request.getTitle()),
                    trimToNull(request.getContent()),
                    sourceType,
                    trimToNull(request.getSourcePath()),
                    startTime,
                    endTime
            );
            page.setTotal(total == null ? 0L : total);
            page.setRecords(knowledgeBaseService.pageKnowledge(
                    trimToNull(request.getTitle()),
                    trimToNull(request.getContent()),
                    sourceType,
                    trimToNull(request.getSourcePath()),
                    startTime,
                    endTime,
                    pageNo,
                    pageSize
            ));
            IPage<KnowledgeChunkRecord> result = page;
            return ResponseEntity.ok(PagedResponse.from(result));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error("查询知识库失败: " + e.getMessage()));
        }
    }

    /**
     * 去除输入两端空白，并在空串时返回 null，便于下游按空条件忽略过滤。
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
