package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.store.RetrievalStateStore;

/**
 * 检索状态存储实现，负责持久化当前会话的检索计划与检索结果状态。
 */
@Service
public class RetrievalStateStoreImpl extends AbstractJsonStateStore<RetrievalState> implements RetrievalStateStore {

    public RetrievalStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public RetrievalState load(String sessionId) {
        /**
         * 读取会话当前检索状态。
         */
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, RetrievalState state) {
        /**
         * 保存最新检索状态，供后续召回和恢复分支复用。
         */
        saveState(sessionId, state);
    }

    @Override
    protected String slotName() {
        return "state.retrieval";
    }

    @Override
    protected int priority() {
        return 90;
    }

    @Override
    protected Class<RetrievalState> modelType() {
        return RetrievalState.class;
    }
}
