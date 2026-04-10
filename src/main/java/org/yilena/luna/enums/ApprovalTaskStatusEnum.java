package org.yilena.luna.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 审批任务状态枚举，定义工具审批任务在流转过程中的状态。
 */
@Getter
@AllArgsConstructor
public enum ApprovalTaskStatusEnum {
    /**
     * 审批任务已创建，等待用户审批。
     */
    PENDING_APPROVAL("PENDING_APPROVAL", "等待审批"),
    /**
     * 审批已通过，工具正在执行。
     */
    RUNNING("RUNNING", "执行中"),
    /**
     * 审批流程及工具执行已完成。
     */
    COMPLETED("COMPLETED", "执行完成"),
    /**
     * 用户拒绝了审批请求。
     */
    REJECTED("REJECTED", "审批拒绝"),
    /**
     * 工具执行或审批恢复流程失败。
     */
    FAILED("FAILED", "执行失败");

    @JsonValue
    /**
     * 对外序列化使用的状态编码。
     */
    private final String code;
    /**
     * 状态中文描述。
     */
    private final String desc;

    /**
     * 根据状态编码解析枚举，未命中时返回空值。
     */
    public static ApprovalTaskStatusEnum ofCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }
}
