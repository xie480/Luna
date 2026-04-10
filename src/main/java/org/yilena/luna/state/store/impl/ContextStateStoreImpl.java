package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.store.ContextStateStore;

/**
 * 上下文状态存储实现，负责读写会话级上下文状态快照，供摘要和主模型链路复用。
 */
@Service
public class ContextStateStoreImpl extends AbstractJsonStateStore<ContextState> implements ContextStateStore {

    public ContextStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public ContextState load(String sessionId) {
        /**
         * 按会话读取当前上下文状态。
         */
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, ContextState state) {
        /**
         * 将最新上下文状态写入标准状态槽位。
         */
        saveState(sessionId, state);
    }

    @Override
    protected String slotName() {
        return "state.context";
    }

    @Override
    protected int priority() {
        return 92;
    }

    @Override
    protected Class<ContextState> modelType() {
        return ContextState.class;
    }
}
