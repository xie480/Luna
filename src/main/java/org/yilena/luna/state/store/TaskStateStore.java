package org.yilena.luna.state.store;

import org.yilena.luna.state.model.TaskState;

public interface TaskStateStore {
    TaskState load(String sessionId);

    void save(String sessionId, TaskState state);
}

