package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.yilena.luna.mapper.StateStoreMapper;

/**
 * JSON 状态存储抽象基类，负责统一封装状态槽位的读取、写入和删除逻辑，供各类状态仓储实现复用。
 */
@RequiredArgsConstructor
abstract class AbstractJsonStateStore<T> {

    protected final StateStoreMapper stateStoreMapper;
    protected final ObjectMapper objectMapper;

    protected abstract String slotName();

    protected abstract int priority();

    protected abstract Class<T> modelType();

    protected T loadState(String sessionId) {
        /**
         * 先按会话和槽位读取原始 JSON，再反序列化为目标状态模型，读取失败时返回空状态。
         */
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            var row = stateStoreMapper.selectStateSlot(sessionId, slotName());
            if (row == null || row.isEmpty()) {
                return null;
            }
            Object raw = row.get("slot_value_json");
            if (raw == null) {
                return null;
            }
            return objectMapper.readValue(String.valueOf(raw), modelType());
        } catch (Exception ignore) {
            return null;
        }
    }

    protected void saveState(String sessionId, T state) {
        /**
         * 写入状态前先确保工作内存载体存在，再按槽位和优先级做统一 upsert。
         */
        if (sessionId == null || sessionId.isBlank() || state == null) {
            return;
        }
        try {
            stateStoreMapper.ensureTaskWorkingMemory(sessionId);
            String payload = objectMapper.writeValueAsString(state);
            stateStoreMapper.upsertStateSlot(
                    sessionId,
                    slotName(),
                    payload,
                    priority(),
                    "STATE_STORE",
                    slotName()
            );
        } catch (Exception ignore) {
        }
    }

    protected void deleteState(String sessionId) {
        /**
         * 删除状态时按会话和槽位精确清理，避免影响其他状态通道。
         */
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            stateStoreMapper.deleteStateSlot(sessionId, slotName());
        } catch (Exception ignore) {
        }
    }
}
