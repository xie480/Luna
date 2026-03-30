package org.yilena.luna.enums; // define package

import com.baomidou.mybatisplus.annotation.EnumValue; // import dependency
import com.fasterxml.jackson.annotation.JsonValue; // import dependency
import lombok.AllArgsConstructor; // import dependency
import lombok.Getter; // import dependency

@Getter // declare annotation
@AllArgsConstructor // declare annotation
public enum LogType { // define enum
    LUNA_OUTPUT(0, "LUNA_OUTPUT", "Luna 输出内容"), // enum or const item
    TOOL_CALL(1, "TOOL_CALL", "Tool 调用"), // enum or const item
    ERROR(2, "ERROR", "系统异常"), // enum or const item
    SELF_UPDATE(3, "SELF_UPDATE", "Luna 自我更新"), // enum or const item
    SYSTEM_EVENT(4, "SYSTEM_EVENT", "系统行为"), // enum or const item
    API_CALL(5, "API_CALL", "接口调用"); // enum or const item

    @EnumValue // declare annotation
    private final Integer code; // business logic

    @JsonValue // declare annotation
    private final String value; // business logic

    private final String desc; // business logic
} // block end
