package org.yilena.luna.state.store;

import org.yilena.luna.state.model.RecoveryState;

public interface RecoveryStateStore {
    RecoveryState load(String sessionId);

    void save(String sessionId, RecoveryState state);

    void clear(String sessionId);
}
