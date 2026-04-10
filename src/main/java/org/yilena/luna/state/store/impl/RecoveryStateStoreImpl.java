package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.StateStoreMapper;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.store.RecoveryStateStore;

/**
 * 恢复状态存储实现，负责持久化中断恢复相关状态，支撑恢复分支继续推进。
 */
@Service
public class RecoveryStateStoreImpl extends AbstractJsonStateStore<RecoveryState> implements RecoveryStateStore {

    public RecoveryStateStoreImpl(StateStoreMapper stateStoreMapper, ObjectMapper objectMapper) {
        super(stateStoreMapper, objectMapper);
    }

    @Override
    public RecoveryState load(String sessionId) {
        /**
         * 读取当前会话的恢复状态。
         */
        return loadState(sessionId);
    }

    @Override
    public void save(String sessionId, RecoveryState state) {
        /**
         * 保存恢复状态，供下一轮判断是否进入恢复流程。
         */
        saveState(sessionId, state);
    }

    @Override
    public void clear(String sessionId) {
        /**
         * 恢复流程结束后清空恢复状态，避免后续正常轮次误判为恢复场景。
         */
        deleteState(sessionId);
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
