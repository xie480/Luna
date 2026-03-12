package org.yilena.luna.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任務狀態枚舉
 */
@Getter
@AllArgsConstructor
public enum TaskStatus {
    PENDING(0, "待处理"),
    COMPLETED(1, "已完成"),
    CANCELLED(2, "已取消"),
    EXPIRED(3, "已过期");

    @EnumValue
    private final Integer code;
    private final String desc;
}
