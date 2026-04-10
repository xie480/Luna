package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.state.store.ToolStateStore;

/**
 * 工具状态存储实现，负责持久化工具调用链路中的中间状态和最近执行结果。
 */
@Service
public class ToolStateStoreImpl extends AbstractJsonStateStore<ToolState> implements ToolStateStore {

    public ToolStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public ToolState load(String sessionId) {
        /**
         * 读取当前会话的工具状态。
         */
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, ToolState state) {
        /**
         * 保存工具状态，供工具语义解析和恢复流程复用。
         */
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
