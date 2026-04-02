package org.yilena.luna.state.store;

import org.yilena.luna.state.model.RetrievalState;

public interface RetrievalStateStore {
    RetrievalState load(String sessionId);

    void save(String sessionId, RetrievalState state);
}

