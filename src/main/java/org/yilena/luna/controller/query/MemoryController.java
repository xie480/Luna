package org.yilena.luna.controller.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.entity.query.MemoryPageQueryRequest;
import org.yilena.luna.entity.query.PagedResponse;
import org.yilena.luna.enums.MemoryType;
import org.yilena.luna.service.MemoryService;

@RestController
@RequestMapping("/luna/api/query/memory")
@RequiredArgsConstructor
@Tag(name = "长期记忆接口")
/**
 * MemoryController ??
 */
public class MemoryController extends BasePageQueryController {

    private final MemoryService memoryService;

    @PostMapping({"", "/page"})
    @Operation(summary = "分页查询长期记忆")
    public ResponseEntity<Object> page(@RequestBody(required = false) MemoryPageQueryRequest req) {
        try {
            // 请求体为空时使用默认查询对象，保证分页参数可用。
            MemoryPageQueryRequest request = req == null ? new MemoryPageQueryRequest() : req;
            Page<Memory> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize()));

            // 按入参动态拼接查询条件，仅对非空字段生效。
            LambdaQueryWrapper<Memory> wrapper = new LambdaQueryWrapper<>();
            if (hasText(request.getSessionId())) {
                wrapper.eq(Memory::getSessionId, request.getSessionId().trim());
            }
            if (hasText(request.getMemoryType())) {
                wrapper.eq(Memory::getMemoryType, MemoryType.valueOf(request.getMemoryType().trim().toUpperCase()));
            }
            if (hasText(request.getContent())) {
                wrapper.like(Memory::getContent, request.getContent().trim());
            }
            if (request.getMinWeight() != null) {
                wrapper.ge(Memory::getWeight, request.getMinWeight());
            }
            if (request.getMaxWeight() != null) {
                wrapper.le(Memory::getWeight, request.getMaxWeight());
            }
            if (hasText(request.getStartTime())) {
                wrapper.ge(Memory::getCreatedAt, parseDateTime(request.getStartTime()));
            }
            if (hasText(request.getEndTime())) {
                wrapper.le(Memory::getCreatedAt, parseDateTime(request.getEndTime()));
            }
            wrapper.orderByDesc(Memory::getCreatedAt);

            // 执行分页查询并统一封装分页返回结构。
            IPage<Memory> result = memoryService.page(page, wrapper);
            return ResponseEntity.ok(PagedResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error("查询长期记忆失败: " + e.getMessage()));
        }
    }
}
