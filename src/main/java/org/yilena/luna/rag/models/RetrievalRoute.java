package org.yilena.luna.rag.models;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 检索流程路由枚举，标识检索应走的 pipeline 类型。
 */
public enum RetrievalRoute {
    SEARCH("search"), // 执行当前逻辑
    NATIVE("native"), // 执行当前逻辑
    MODULAR("modular"), // 执行当前逻辑
    AGENTIC("agentic"); // 执行语句逻辑

    private final String value; // 声明成员字段

    RetrievalRoute(String value) { // 开始新的代码块
        this.value = value; // 执行赋值操作
    } // 结束当前代码块

    public String value() { // 定义方法签名
        return value; // 返回处理结果
    } // 结束当前代码块

    public static Optional<RetrievalRoute> fromValue(String raw) { // 定义方法签名
        if (raw == null || raw.isBlank()) { // 进行条件判断
            return Optional.empty(); // 返回处理结果
        } // 结束当前代码块
        String normalized = raw.trim().toLowerCase(Locale.ROOT); // 执行赋值操作
        return Arrays.stream(values()).filter(route -> route.value.equals(normalized)).findFirst(); // 返回处理结果
    } // 结束当前代码块

    public static List<RetrievalRoute> all() { // 定义方法签名
        return List.of(values()); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
