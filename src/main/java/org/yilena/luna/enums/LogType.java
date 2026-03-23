package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LogType {
    LUNA_OUTPUT(0, "LUNA_OUTPUT", "Luna 输出内容"),
    TOOL_CALL(1, "TOOL_CALL", "Tool 调用"),
    ERROR(2, "ERROR", "系统异常"),
    SELF_UPDATE(3, "SELF_UPDATE", "Luna 自我更新"),
    SYSTEM_EVENT(4, "SYSTEM_EVENT", "系统行为"),
    API_CALL(5, "API_CALL", "接口调用");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
