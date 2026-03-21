package org.yilena.luna.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.entity.LunaLog;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.entity.query.KnowledgeBasePageQueryRequest;
import org.yilena.luna.entity.query.LunaLogPageQueryRequest;
import org.yilena.luna.entity.query.MemoryPageQueryRequest;
import org.yilena.luna.entity.query.PagedResponse;
import org.yilena.luna.entity.query.UserPreferencePageQueryRequest;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.MemoryType;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.service.KnowledgeBaseService;
import org.yilena.luna.service.LunaLogService;
import org.yilena.luna.service.MemoryService;
import org.yilena.luna.service.UserPreferenceService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/luna/api/query")
@RequiredArgsConstructor
@Tag(name = "分页条件查询接口")
public class QueryController {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KnowledgeBaseService knowledgeBaseService;
    private final UserPreferenceService userPreferenceService;
    private final MemoryService memoryService;
    private final LunaLogService lunaLogService;

    @PostMapping("/knowledge-base")
    @Operation(summary = "分页查询知识库")
    public ResponseEntity<Object> pageKnowledgeBase(@RequestBody(required = false) KnowledgeBasePageQueryRequest req) {
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

    @PostMapping("/user-preference")
    @Operation(summary = "分页查询用户偏好")
    public ResponseEntity<Object> pageUserPreference(@RequestBody(required = false) UserPreferencePageQueryRequest req) {
        try {
            UserPreferencePageQueryRequest request = req == null ? new UserPreferencePageQueryRequest() : req;
            Page<UserPreference> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize()));

            LambdaQueryWrapper<UserPreference> wrapper = new LambdaQueryWrapper<>();
            if (hasText(request.getPrefKey())) {
                wrapper.like(UserPreference::getPrefKey, request.getPrefKey().trim());
            }
            if (hasText(request.getPrefValue())) {
                wrapper.like(UserPreference::getPrefValue, request.getPrefValue().trim());
            }
            if (hasText(request.getDescription())) {
                wrapper.like(UserPreference::getDescription, request.getDescription().trim());
            }
            if (hasText(request.getStartTime())) {
                wrapper.ge(UserPreference::getCreatedAt, parseDateTime(request.getStartTime()));
            }
            if (hasText(request.getEndTime())) {
                wrapper.le(UserPreference::getCreatedAt, parseDateTime(request.getEndTime()));
            }
            wrapper.orderByDesc(UserPreference::getCreatedAt);

            IPage<UserPreference> result = userPreferenceService.page(page, wrapper);
            return ResponseEntity.ok(PagedResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error("查询用户偏好失败: " + e.getMessage()));
        }
    }

    @PostMapping("/memory")
    @Operation(summary = "分页查询长期记忆")
    public ResponseEntity<Object> pageMemory(@RequestBody(required = false) MemoryPageQueryRequest req) {
        try {
            MemoryPageQueryRequest request = req == null ? new MemoryPageQueryRequest() : req;
            Page<Memory> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize()));

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

            IPage<Memory> result = memoryService.page(page, wrapper);
            return ResponseEntity.ok(PagedResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error("查询长期记忆失败: " + e.getMessage()));
        }
    }

    @PostMapping("/log")
    @Operation(summary = "分页查询日志")
    public ResponseEntity<Object> pageLog(@RequestBody(required = false) LunaLogPageQueryRequest req) {
        try {
            LunaLogPageQueryRequest request = req == null ? new LunaLogPageQueryRequest() : req;
            Page<LunaLog> page = new Page<>(normalizePageNo(request.getPageNo()), normalizePageSize(request.getPageSize()));

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

            IPage<LunaLog> result = lunaLogService.page(page, wrapper);
            return ResponseEntity.ok(PagedResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("参数错误: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error("查询日志失败: " + e.getMessage()));
        }
    }

    private LocalDateTime parseDateTime(String text) {
        return LocalDateTime.parse(text.trim(), DATE_TIME_FORMATTER);
    }

    private long normalizePageNo(Long pageNo) {
        if (pageNo == null || pageNo < 1) return 1L;
        return pageNo;
    }

    private long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) return 10L;
        return Math.min(pageSize, 200L);
    }

    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private Object error(String message) {
        return java.util.Map.of(
                "status", "error",
                "message", message
        );
    }
}
