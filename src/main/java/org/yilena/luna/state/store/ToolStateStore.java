package org.yilena.luna.state.store;

import org.yilena.luna.state.model.ToolState;

public interface ToolStateStore {
    ToolState load(String sessionId);

    void save(String sessionId, ToolState state);
}

