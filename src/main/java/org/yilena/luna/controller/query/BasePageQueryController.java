package org.yilena.luna.controller.query;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 分页查询控制器基类
 * 提供分页参数规范化、时间解析、通用错误响应
 */
public abstract class BasePageQueryController {

    protected static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); // 定义方法签名

    protected LocalDateTime parseDateTime(String text) { // 定义方法签名
        return LocalDateTime.parse(text.trim(), DATE_TIME_FORMATTER); // 返回处理结果
    } // 结束当前代码块

    protected long normalizePageNo(Long pageNo) { // 定义方法签名
        if (pageNo == null || pageNo < 1) return 1L; // 进行条件判断
        return pageNo; // 返回处理结果
    } // 结束当前代码块

    protected long normalizePageSize(Long pageSize) { // 定义方法签名
        if (pageSize == null || pageSize < 1) return 10L; // 进行条件判断
        return Math.min(pageSize, 200L); // 返回处理结果
    } // 结束当前代码块

    protected boolean hasText(String s) { // 定义方法签名
        return s != null && !s.trim().isEmpty(); // 返回处理结果
    } // 结束当前代码块

    protected Object error(String message) { // 定义方法签名
        return Map.of( // 返回处理结果
                "status", "error", // 执行当前逻辑
                "message", message // 执行当前逻辑
        ); // 执行语句逻辑
    } // 结束当前代码块
} // 结束当前代码块
