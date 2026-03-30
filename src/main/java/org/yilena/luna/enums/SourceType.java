package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知識庫來源類型枚舉
 */
@Getter
@AllArgsConstructor
public enum SourceType {
    FILE(0, "FILE", "本地文件"), // 执行当前逻辑
    WEB_SEARCH(1, "WEB_SEARCH", "网络搜索"), // 执行当前逻辑
    MANUAL_INPUT(2, "MANUAL_INPUT", "手动输入"); // 执行语句逻辑

    @EnumValue // 声明注解
    private final Integer code; // 声明成员字段

    @JsonValue // 声明注解
    private final String value; // 声明成员字段

    private final String desc; // 声明成员字段
} // 结束当前代码块
