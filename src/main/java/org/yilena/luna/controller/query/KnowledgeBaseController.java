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
@Tag(name = "知识库接口")
/**
 * KnowledgeBaseController ??
 */
public class KnowledgeBaseController extends BasePageQueryController {

    private final KnowledgeBaseService knowledgeBaseService; // 声明成员字段

    @PostMapping({"", "/page"}) // 声明注解
    @Operation(summary = "分页查询知识库") // 声明注解
    public ResponseEntity<Object> page(@RequestBody(required = false) KnowledgeBasePageQueryRequest req) { // 定义方法签名
        try { // 尝试执行核心逻辑
            KnowledgeBasePageQueryRequest request = req == null ? new KnowledgeBasePageQueryRequest() : req; // 执行赋值操作
            Page<KnowledgeBase> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize())); // 执行赋值操作

            LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>(); // 执行赋值操作
            if (hasText(request.getTitle())) { // 进行条件判断
                wrapper.like(KnowledgeBase::getTitle, request.getTitle().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getContent())) { // 进行条件判断
                wrapper.like(KnowledgeBase::getContent, request.getContent().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getSourceType())) { // 进行条件判断
                wrapper.eq(KnowledgeBase::getSourceType, SourceType.valueOf(request.getSourceType().trim().toUpperCase())); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getSourcePath())) { // 进行条件判断
                wrapper.like(KnowledgeBase::getSourcePath, request.getSourcePath().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getStartTime())) { // 进行条件判断
                wrapper.ge(KnowledgeBase::getCreatedAt, parseDateTime(request.getStartTime())); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getEndTime())) { // 进行条件判断
                wrapper.le(KnowledgeBase::getCreatedAt, parseDateTime(request.getEndTime())); // 执行语句逻辑
            } // 结束当前代码块
            wrapper.orderByDesc(KnowledgeBase::getCreatedAt); // 执行语句逻辑

            IPage<KnowledgeBase> result = knowledgeBaseService.page(page, wrapper); // 执行赋值操作
            return ResponseEntity.ok(PagedResponse.from(result)); // 返回处理结果
        } catch (IllegalArgumentException e) { // 开始新的代码块
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage())); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return ResponseEntity.internalServerError().body(error("查询知识库失败: " + e.getMessage())); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
