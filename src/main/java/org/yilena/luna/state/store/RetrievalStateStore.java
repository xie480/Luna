package org.yilena.luna.state.store;

import org.yilena.luna.state.model.RetrievalState;

/**
 * 检索状态存储接口，负责保存会话当前检索计划、查询语句和证据选择结果，
 * 便于多轮链路复用检索上下文。
 */
public interface RetrievalStateStore {
    RetrievalState load(String sessionId);

    void save(String sessionId, RetrievalState state);
}
