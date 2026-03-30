package org.yilena.luna.rag.models;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 检索数据源枚举，定义可参与召回的语料域。
 */
public enum RetrievalSource {
    KNOWLEDGE("knowledge"), // 执行当前逻辑
    MEMORY("memory"), // 执行当前逻辑
    PREFERENCE("preference"); // 执行语句逻辑

    private final String value; // 声明成员字段

    RetrievalSource(String value) { // 开始新的代码块
        this.value = value; // 执行赋值操作
    } // 结束当前代码块

    public String value() { // 定义方法签名
        return value; // 返回处理结果
    } // 结束当前代码块

    public static Optional<RetrievalSource> fromValue(String raw) { // 定义方法签名
        if (raw == null || raw.isBlank()) { // 进行条件判断
            return Optional.empty(); // 返回处理结果
        } // 结束当前代码块
        String normalized = raw.trim().toLowerCase(Locale.ROOT); // 执行赋值操作
        return Arrays.stream(values()).filter(source -> source.value.equals(normalized)).findFirst(); // 返回处理结果
    } // 结束当前代码块

    public static List<RetrievalSource> all() { // 定义方法签名
        return List.of(values()); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
