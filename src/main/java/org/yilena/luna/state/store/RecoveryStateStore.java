package org.yilena.luna.state.store;

import org.yilena.luna.state.model.RecoveryState;

/**
 * 恢复状态存储接口，负责持久化和清理恢复链路所需的中断状态，
 * 保证系统恢复时能够准确判断恢复入口与恢复上下文。
 */
public interface RecoveryStateStore {
    RecoveryState load(String sessionId);

    void save(String sessionId, RecoveryState state);

    void clear(String sessionId);
}
