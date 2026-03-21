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
    SUCCESS(0, "SUCCESS", "打开成功"),
    FAILED(1, "FAILED", "打开失败");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;
}
