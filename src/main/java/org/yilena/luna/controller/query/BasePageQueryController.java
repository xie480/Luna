package org.yilena.luna.controller.query;

import org.yilena.luna.constants.DateTimeConstant;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.ResultStatusConstants;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Base controller for page query APIs.
 */
public abstract class BasePageQueryController {

    protected static final long DEFAULT_PAGE_NO = 1L;
    protected static final long DEFAULT_PAGE_SIZE = 10L;
    protected static final long MAX_PAGE_SIZE = 200L;

    protected static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DateTimeConstant.FORMAT_YYYYMMDDHHMMSS);

    protected LocalDateTime parseDateTime(String text) {
        return LocalDateTime.parse(text.trim(), DATE_TIME_FORMATTER);
    }

    protected long normalizePageNo(Long pageNo) {
        if (pageNo == null || pageNo < DEFAULT_PAGE_NO) {
            return DEFAULT_PAGE_NO;
        }
        return pageNo;
    }

    protected long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < DEFAULT_PAGE_NO) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    protected boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    protected Object error(String message) {
        return Map.of(
                JsonFieldConstants.STATUS, ResultStatusConstants.ERROR,
                JsonFieldConstants.MESSAGE, message
        );
    }
}
