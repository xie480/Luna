package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.store.TaskStateStore;

/**
 * 任务状态存储实现，负责持久化会话当前任务态和节点推进状态。
 */
@Service
public class TaskStateStoreImpl extends AbstractJsonStateStore<TaskState> implements TaskStateStore {

    public TaskStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public TaskState load(String sessionId) {
        /**
         * 读取当前会话的任务状态。
         */
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, TaskState state) {
        /**
         * 将最新任务状态写入标准槽位，供编排器和执行器共享。
         */
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
