package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LogType {
    LUNA_OUTPUT(0, "Luna 输出内容"),
    TOOL_CALL(1, "Tool 调用"),
    ERROR(2, "系统异常"),
    SELF_UPDATE(3, "Luna 自我更新"),
    SYSTEM_EVENT(4, "系统行为"),
    API_CALL(5, "接口调用");

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;
}
