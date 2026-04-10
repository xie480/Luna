package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 日志类型枚举，用于区分系统日志记录的业务来源。
 */
@Getter
@AllArgsConstructor
public enum LogType {
    /**
     * Luna 输出内容日志。
     */
    LUNA_OUTPUT(0, "LUNA_OUTPUT", "Luna 输出内容"),
    /**
     * 工具调用日志。
     */
    TOOL_CALL(1, "TOOL_CALL", "工具调用"),
    /**
     * 系统异常日志。
     */
    ERROR(2, "ERROR", "系统异常"),
    /**
     * 自我更新日志。
     */
    SELF_UPDATE(3, "SELF_UPDATE", "自我更新"),
    /**
     * 系统事件日志。
     */
    SYSTEM_EVENT(4, "SYSTEM_EVENT", "系统事件"),
    /**
     * 接口调用日志。
     */
    API_CALL(5, "API_CALL", "接口调用");

    @EnumValue
    /**
     * 持久化到数据库中的数值编码。
     */
    private final Integer code;

    @JsonValue
    /**
     * 对外序列化使用的类型值。
     */
    private final String value;

    /**
     * 类型中文描述。
     */
    private final String desc;
}
