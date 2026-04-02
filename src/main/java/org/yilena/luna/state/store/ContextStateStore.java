package org.yilena.luna.state.store;

import org.yilena.luna.state.model.ContextState;

public interface ContextStateStore {
    ContextState load(String sessionId);

    void save(String sessionId, ContextState state);
}

