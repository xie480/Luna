package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
/**
 * 恢复状态模型，负责记录会话被中断后的恢复上下文，
 * 供系统恢复事件触发时判断从哪个节点继续推进。
 */
public class RecoveryState {
    /**
     * 记录中断发生时间。
     */
    String interruptedAt;
    /**
     * 记录本次中断原因。
     */
    String interruptReason;
    /**
     * 记录触发恢复的事件类型。
     */
    String recoveryEvent;
    /**
     * 记录恢复链路关联的快照标识。
     */
    String recoverySnapshotId;
}
