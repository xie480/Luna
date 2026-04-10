package org.yilena.luna.controller.query;

import org.yilena.luna.constants.DateTimeConstant;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.ResultStatusConstants;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 分页查询控制器基类，负责提供通用的分页、时间解析和错误响应辅助能力。
 */
public abstract class BasePageQueryController {

    /**
     * 默认页码，从第一页开始。
     */
    protected static final long DEFAULT_PAGE_NO = 1L;
    /**
     * 默认分页大小。
     */
    protected static final long DEFAULT_PAGE_SIZE = 10L;
    /**
     * 单次查询允许的最大分页大小，避免过量数据查询。
     */
    protected static final long MAX_PAGE_SIZE = 200L;

    /**
     * 分页查询接口统一使用的日期时间格式化器。
     */
    protected static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDDHHMMSS);

    /**
     * 将字符串时间解析为本地时间对象，供查询条件构造使用。
     */
    protected LocalDateTime parseDateTime(String text) {
        return LocalDateTime.parse(text.trim(), DATE_TIME_FORMATTER);
    }

    /**
     * 规范化页码，避免非法页码影响分页查询。
     */
    protected long normalizePageNo(Long pageNo) {
        if (pageNo == null || pageNo < DEFAULT_PAGE_NO) {
            return DEFAULT_PAGE_NO;
        }
        return pageNo;
    }

    /**
     * 规范化分页大小，并限制最大值，保护数据库查询性能。
     */
    protected long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < DEFAULT_PAGE_NO) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 判断字符串是否包含有效文本。
     */
    protected boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * 构造统一的错误响应体，减少各查询接口重复代码。
     */
    protected Object error(String message) {
        return Map.of(
                JsonFieldConstants.STATUS, ResultStatusConstants.ERROR,
                JsonFieldConstants.MESSAGE, message
        );
    }
}
