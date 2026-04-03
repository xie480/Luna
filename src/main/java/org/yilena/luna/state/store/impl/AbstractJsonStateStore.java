package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.yilena.luna.mapper.StateStoreMapper;

@RequiredArgsConstructor
abstract class AbstractJsonStateStore<T> {

    protected final StateStoreMapper stateStoreMapper;
    protected final ObjectMapper objectMapper;

    protected abstract String slotName();

    protected abstract int priority();

    protected abstract Class<T> modelType();

    protected T loadState(String sessionId) {
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
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            stateStoreMapper.deleteStateSlot(sessionId, slotName());
        } catch (Exception ignore) {
        }
    }
}
