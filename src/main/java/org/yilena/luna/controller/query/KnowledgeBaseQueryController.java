package org.yilena.luna.controller.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.entity.query.KnowledgeBasePageQueryRequest;
import org.yilena.luna.entity.query.PagedResponse;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.service.KnowledgeBaseService;

@RestController
@RequestMapping("/luna/api/query/knowledge-base")
@RequiredArgsConstructor
@Tag(name = "知识库分页查询")
public class KnowledgeBaseQueryController extends BasePageQueryController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping({"", "/page"})
    @Operation(summary = "分页查询知识库")
    public ResponseEntity<Object> page(@RequestBody(required = false) KnowledgeBasePageQueryRequest req) {
        try {
            KnowledgeBasePageQueryRequest request = req == null ? new KnowledgeBasePageQueryRequest() : req;
            Page<KnowledgeBase> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize()));

            LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
            if (hasText(request.getTitle())) {
                wrapper.like(KnowledgeBase::getTitle, request.getTitle().trim());
            }
            if (hasText(request.getContent())) {
                wrapper.like(KnowledgeBase::getContent, request.getContent().trim());
            }
            if (hasText(request.getSourceType())) {
                wrapper.eq(KnowledgeBase::getSourceType, SourceType.valueOf(request.getSourceType().trim().toUpperCase()));
            }
            if (hasText(request.getSourcePath())) {
                wrapper.like(KnowledgeBase::getSourcePath, request.getSourcePath().trim());
            }
            if (hasText(request.getStartTime())) {
                wrapper.ge(KnowledgeBase::getCreatedAt, parseDateTime(request.getStartTime()));
            }
            if (hasText(request.getEndTime())) {
                wrapper.le(KnowledgeBase::getCreatedAt, parseDateTime(request.getEndTime()));
            }
            wrapper.orderByDesc(KnowledgeBase::getCreatedAt);

            IPage<KnowledgeBase> result = knowledgeBaseService.page(page, wrapper);
            return ResponseEntity.ok(PagedResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error("查询知识库失败: " + e.getMessage()));
        }
    }
}
