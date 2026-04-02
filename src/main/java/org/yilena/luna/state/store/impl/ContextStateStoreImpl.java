package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.store.ContextStateStore;

@Service
public class ContextStateStoreImpl extends AbstractJsonStateStore<ContextState> implements ContextStateStore {

    public ContextStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public ContextState load(String sessionId) {
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, ContextState state) {
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

