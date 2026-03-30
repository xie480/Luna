package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 报告唤起浏览器结果
 */
@Getter
@AllArgsConstructor
public enum PlanOpenResult {
    SUCCESS(0, "SUCCESS", "打开成功"), // 执行当前逻辑
    FAILED(1, "FAILED", "打开失败"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
