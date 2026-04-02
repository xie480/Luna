package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.state.store.ToolStateStore;

@Service
public class ToolStateStoreImpl extends AbstractJsonStateStore<ToolState> implements ToolStateStore {

    public ToolStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public ToolState load(String sessionId) {
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, ToolState state) {
        saveState(sessionId, state);
    }

    @Override
    protected String slotName() {
        return "state.tool";
    }

    @Override
    protected int priority() {
        return 88;
    }

    @Override
    protected Class<ToolState> modelType() {
        return ToolState.class;
    }
}

