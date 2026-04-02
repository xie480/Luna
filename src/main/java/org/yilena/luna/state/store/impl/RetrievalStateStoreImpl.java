package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.store.RetrievalStateStore;

@Service
public class RetrievalStateStoreImpl extends AbstractJsonStateStore<RetrievalState> implements RetrievalStateStore {

    public RetrievalStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public RetrievalState load(String sessionId) {
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, RetrievalState state) {
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

