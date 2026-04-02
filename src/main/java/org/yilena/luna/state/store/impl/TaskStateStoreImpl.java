package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.store.TaskStateStore;

@Service
public class TaskStateStoreImpl extends AbstractJsonStateStore<TaskState> implements TaskStateStore {

    public TaskStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public TaskState load(String sessionId) {
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, TaskState state) {
        saveState(sessionId, state);
    }

    @Override
    protected String slotName() {
        return "state.task";
    }

    @Override
    protected int priority() {
        return 95;
    }

    @Override
    protected Class<TaskState> modelType() {
        return TaskState.class;
    }
}

