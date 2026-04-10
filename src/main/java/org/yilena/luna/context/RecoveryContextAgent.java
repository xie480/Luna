package org.yilena.luna.context;

import org.yilena.luna.memory.model.StructuredContextPackage;

/**
 * 恢复上下文代理接口，负责在中断恢复场景下重建结构化上下文并给出恢复决策。
 */
public interface RecoveryContextAgent {
    /**
     * 根据恢复事件和中断原因恢复当前会话上下文。
     */
    StructuredContextPackage recover(String sessionId,
                                     StructuredContextPackage contextPackage,
                                     String recoveryEvent,
                                     String interruptReason);
}
