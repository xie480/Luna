package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * MCP 敏感度/权限等级枚举
 */
@Getter
public enum Sensitivity {
    LOW("LOW", "低敏感度(默认允许)"), // 执行当前逻辑
    MEDIUM("MEDIUM", "中敏感度(可能需要记录或轻度审计)"), // 执行当前逻辑
    HIGH("HIGH", "高敏感度(严格拦截或必须审批)"); // 执行语句逻辑

    @EnumValue // 声明注解
    @JsonValue // 声明注解
    private final String value; // 声明成员字段
    private final String desc; // 声明成员字段

    Sensitivity(String value, String desc) { // 开始新的代码块
        this.value = value; // 执行赋值操作
        this.desc = desc; // 执行赋值操作
    } // 结束当前代码块
} // 结束当前代码块
