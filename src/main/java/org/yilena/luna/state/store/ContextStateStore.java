package org.yilena.luna.state.store;

import org.yilena.luna.state.model.ContextState;

/**
 * 上下文状态存储接口，负责按会话读取和写入上下文状态快照，
 * 让后续轮次能够复用最新激活的上下文边界。
 */
public interface ContextStateStore {
    ContextState load(String sessionId);

    void save(String sessionId, ContextState state);
}
