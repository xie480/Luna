package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * MCP 执行模式枚举
 */
@Getter
public enum RunMode {
    SYNC("SYNC", "同步执行"), // 执行当前逻辑
    ASYNC("ASYNC", "异步执行"); // 执行语句逻辑

    @EnumValue // 声明注解
    @JsonValue // 声明注解
    private final String value; // 声明成员字段
    private final String desc; // 声明成员字段

    RunMode(String value, String desc) { // 开始新的代码块
        this.value = value; // 执行赋值操作
        this.desc = desc; // 执行赋值操作
    } // 结束当前代码块
} // 结束当前代码块
