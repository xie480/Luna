package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.store.RecoveryStateStore;

@Service
public class RecoveryStateStoreImpl extends AbstractJsonStateStore<RecoveryState> implements RecoveryStateStore {

    public RecoveryStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public RecoveryState load(String sessionId) {
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, RecoveryState state) {
        saveState(sessionId, state);
    }

    @Override
    protected String slotName() {
        return "state.recovery";
    }

    @Override
    protected int priority() {
        return 86;
    }

    @Override
    protected Class<RecoveryState> modelType() {
        return RecoveryState.class;
    }
}

