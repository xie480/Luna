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
@Tag(name = "日志查询接口", description = "提供运行日志的条件分页查询能力")
/**
 * 日志查询控制器，负责按日志类型、模块、行为和时间范围分页检索系统日志。
 */
public class LunaLogController extends BasePageQueryController {

    /**
     * 日志服务，负责执行日志分页查询。
     */
    private final LunaLogService lunaLogService;

    @PostMapping({"", "/page"})
    /**
     * 按多维条件分页查询运行日志。
     *
     * 该接口会根据非空入参动态拼接查询条件，并统一按创建时间倒序返回结果。
     */
    @Operation(summary = "分页查询日志", description = "根据日志类型、模块、行为、内容、追踪标识和时间范围分页查询日志")
    public ResponseEntity<Object> page(@RequestBody(required = false) LunaLogPageQueryRequest req) {
        try {
            /**
             * 请求体为空时使用默认查询对象，保证分页参数始终可用。
             */
            LunaLogPageQueryRequest request = req == null ? new LunaLogPageQueryRequest() : req;
            Page<LunaLog> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize()));

            /**
             * 按非空条件动态拼接查询条件，避免无意义条件影响查询结果。
             */
            LambdaQueryWrapper<LunaLog> wrapper = new LambdaQueryWrapper<>();
            if (hasText(request.getLogType())) {
                wrapper.eq(LunaLog::getLogType, LogType.valueOf(request.getLogType().trim().toUpperCase()));
            }
            if (hasText(request.getModule())) {
                wrapper.like(LunaLog::getModule, request.getModule().trim());
            }
            if (hasText(request.getAction())) {
                wrapper.like(LunaLog::getAction, request.getAction().trim());
            }
            if (hasText(request.getContent())) {
                wrapper.like(LunaLog::getContent, request.getContent().trim());
            }
            if (hasText(request.getTraceId())) {
                wrapper.eq(LunaLog::getTraceId, request.getTraceId().trim());
            }
            if (hasText(request.getOperatorId())) {
                wrapper.eq(LunaLog::getOperatorId, request.getOperatorId().trim());
            }
            if (hasText(request.getStartTime())) {
                wrapper.ge(LunaLog::getCreateAt, parseDateTime(request.getStartTime()));
            }
            if (hasText(request.getEndTime())) {
                wrapper.le(LunaLog::getCreateAt, parseDateTime(request.getEndTime()));
            }
            wrapper.orderByDesc(LunaLog::getCreateAt);

            /**
             * 执行分页查询并统一包装为标准分页响应结构。
             */
            IPage<LunaLog> result = lunaLogService.page(page, wrapper);
            return ResponseEntity.ok(PagedResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error("查询日志失败: " + e.getMessage()));
        }
    }
}
