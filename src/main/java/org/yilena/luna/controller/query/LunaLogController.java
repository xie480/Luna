package org.yilena.luna.controller.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.LunaLog;
import org.yilena.luna.entity.query.LunaLogPageQueryRequest;
import org.yilena.luna.entity.query.PagedResponse;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.service.LunaLogService;

@RestController
@RequestMapping("/luna/api/query/log")
@RequiredArgsConstructor
@Tag(name = "日志接口")
/**
 * LunaLogController ??
 */
public class LunaLogController extends BasePageQueryController {

    private final LunaLogService lunaLogService; // 声明成员字段

    @PostMapping({"", "/page"}) // 声明注解
    @Operation(summary = "分页查询日志") // 声明注解
    public ResponseEntity<Object> page(@RequestBody(required = false) LunaLogPageQueryRequest req) { // 定义方法签名
        try { // 尝试执行核心逻辑
            LunaLogPageQueryRequest request = req == null ? new LunaLogPageQueryRequest() : req; // 执行赋值操作
            Page<LunaLog> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize())); // 执行赋值操作

            LambdaQueryWrapper<LunaLog> wrapper = new LambdaQueryWrapper<>(); // 执行赋值操作
            if (hasText(request.getLogType())) { // 进行条件判断
                wrapper.eq(LunaLog::getLogType, LogType.valueOf(request.getLogType().trim().toUpperCase())); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getModule())) { // 进行条件判断
                wrapper.like(LunaLog::getModule, request.getModule().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getAction())) { // 进行条件判断
                wrapper.like(LunaLog::getAction, request.getAction().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getContent())) { // 进行条件判断
                wrapper.like(LunaLog::getContent, request.getContent().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getTraceId())) { // 进行条件判断
                wrapper.eq(LunaLog::getTraceId, request.getTraceId().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getOperatorId())) { // 进行条件判断
                wrapper.eq(LunaLog::getOperatorId, request.getOperatorId().trim()); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getStartTime())) { // 进行条件判断
                wrapper.ge(LunaLog::getCreateAt, parseDateTime(request.getStartTime())); // 执行语句逻辑
            } // 结束当前代码块
            if (hasText(request.getEndTime())) { // 进行条件判断
                wrapper.le(LunaLog::getCreateAt, parseDateTime(request.getEndTime())); // 执行语句逻辑
            } // 结束当前代码块
            wrapper.orderByDesc(LunaLog::getCreateAt); // 执行语句逻辑

            IPage<LunaLog> result = lunaLogService.page(page, wrapper); // 执行赋值操作
            return ResponseEntity.ok(PagedResponse.from(result)); // 返回处理结果
        } catch (IllegalArgumentException e) { // 开始新的代码块
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage())); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return ResponseEntity.internalServerError().body(error("查询日志失败: " + e.getMessage())); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
