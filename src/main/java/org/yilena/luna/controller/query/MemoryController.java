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

    private final MemoryService memoryService; // 声明成员字段

    @PostMapping({"", "/page"}) // 声明注解
    @Operation(summary = "分页查询长期记忆") // 声明注解
    public ResponseEntity<Object> page(@RequestBody(required = false) MemoryPageQueryRequest req) { // 定义方法签名
        try { // 尝试执行核心逻辑
            MemoryPageQueryRequest request = req == null ? new MemoryPageQueryRequest() : req; // 执行赋值操作
            Page<Memory> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize())); // 执行赋值操作

            LambdaQueryWrapper<Memory> wrapper = new LambdaQueryWrapper<>(); // 执行赋值操作
            if (hasText(request.getSessionId())) { // 进行条件判断
                wrapper.eq(Memory::getSessionId, request.getSessionId().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getMemoryType())) { // 进行条件判断
                wrapper.eq(Memory::getMemoryType, MemoryType.valueOf(request.getMemoryType().trim().toUpperCase())); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getContent())) { // 进行条件判断
                wrapper.like(Memory::getContent, request.getContent().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (request.getMinWeight() != null) { // 进行条件判断
                wrapper.ge(Memory::getWeight, request.getMinWeight()); // 执行语句逻辑
            } // 结束当前代码块
            if (request.getMaxWeight() != null) { // 进行条件判断
                wrapper.le(Memory::getWeight, request.getMaxWeight()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getStartTime())) { // 进行条件判断
                wrapper.ge(Memory::getCreatedAt, parseDateTime(request.getStartTime())); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getEndTime())) { // 进行条件判断
                wrapper.le(Memory::getCreatedAt, parseDateTime(request.getEndTime())); // 执行语句逻辑
            } // 结束当前代码块
            wrapper.orderByDesc(Memory::getCreatedAt); // 执行语句逻辑

            IPage<Memory> result = memoryService.page(page, wrapper); // 执行赋值操作
            return ResponseEntity.ok(PagedResponse.from(result)); // 返回处理结果
        } catch (IllegalArgumentException e) { // 开始新的代码块
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage())); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return ResponseEntity.internalServerError().body(error("查询长期记忆失败: " + e.getMessage())); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
