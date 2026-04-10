package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 运行模式枚举，用于描述能力调用是同步完成还是异步回调。
 */
@Getter
public enum RunMode {
    /**
     * 同步执行，调用方会在当前请求中等待结果返回。
     */
    SYNC("SYNC", "同步执行"),
    /**
     * 异步执行，调用方先收到受理结果，稍后再通过回调或轮询获取最终结果。
     */
    ASYNC("ASYNC", "异步执行");

    @EnumValue
    @JsonValue
    /**
     * 持久化和序列化使用的模式值。
     */
    private final String value;
    /**
     * 模式中文描述。
     */
    private final String desc;

    RunMode(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}
