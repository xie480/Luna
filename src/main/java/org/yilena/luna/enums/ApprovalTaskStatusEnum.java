package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * Approval task status.
 */
@Getter
@AllArgsConstructor
public enum ApprovalTaskStatusEnum {
    PENDING_APPROVAL("PENDING_APPROVAL", "等待审批"),
    RUNNING("RUNNING", "执行中"),
    COMPLETED("COMPLETED", "执行完成"),
    REJECTED("REJECTED", "审批拒绝"),
    FAILED("FAILED", "执行失败");

    @JsonValue
    private final String code;
    private final String desc;

    public static ApprovalTaskStatusEnum ofCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }
}
