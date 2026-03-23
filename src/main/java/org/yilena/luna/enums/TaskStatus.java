package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任務狀態枚舉
 */
@Getter
@AllArgsConstructor
public enum TaskStatus {
    PENDING(0, "PENDING", "待处理"),
    COMPLETED(1, "COMPLETED", "已完成"),
    CANCELLED(2, "CANCELLED", "已取消"),
    EXPIRED(3, "EXPIRED", "已过期");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
