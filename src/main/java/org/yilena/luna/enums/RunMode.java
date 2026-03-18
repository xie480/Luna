package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * MCP 执行模式枚举
 */
@Getter
public enum RunMode {
    SYNC("SYNC", "同步执行"),
    ASYNC("ASYNC", "异步执行");

    @EnumValue
    @JsonValue
    private final String value;
    private final String desc;

    RunMode(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}
