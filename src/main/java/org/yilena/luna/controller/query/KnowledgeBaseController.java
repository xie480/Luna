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
@Tag(name = "知识库接口")
/**
 * KnowledgeBaseController ??
 */
public class KnowledgeBaseController extends BasePageQueryController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping({"", "/page"})
    @Operation(summary = "分页查询知识库")
    public ResponseEntity<Object> page(@RequestBody(required = false) KnowledgeBasePageQueryRequest req) {
        try {
            // 请求体为空时使用默认查询对象，保证分页参数可用。
            KnowledgeBasePageQueryRequest request = req == null ? new KnowledgeBasePageQueryRequest() : req;
            long pageNo = normalizePageNo(request.getPageNo());
            long pageSize = normalizePageSize(request.getPageSize());
            Page<KnowledgeChunkRecord> page = new Page<>(pageNo, pageSize);
            LocalDateTime startTime = hasText(request.getStartTime()) ? parseDateTime(request.getStartTime()) : null;
            LocalDateTime endTime = hasText(request.getEndTime()) ? parseDateTime(request.getEndTime()) : null;
            String sourceType = hasText(request.getSourceType()) ? request.getSourceType().trim().toUpperCase() : null;

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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
