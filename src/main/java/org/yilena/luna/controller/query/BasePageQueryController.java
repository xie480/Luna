package org.yilena.luna.controller.query;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 分页查询控制器基类
 * 提供分页参数规范化、时间解析、通用错误响应
 */
public abstract class BasePageQueryController {

    protected static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    protected LocalDateTime parseDateTime(String text) {
        return LocalDateTime.parse(text.trim(), DATE_TIME_FORMATTER);
    }

    protected long normalizePageNo(Long pageNo) {
        if (pageNo == null || pageNo < 1) return 1L;
        return pageNo;
    }

    protected long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) return 10L;
        return Math.min(pageSize, 200L);
    }

    protected boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    protected Object error(String message) {
        return Map.of(
                "status", "error",
                "message", message
        );
    }
}
