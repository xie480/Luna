package org.yilena.luna.state.store;

import org.yilena.luna.state.model.ToolState;

/**
 * 工具状态存储接口，负责保存最近工具执行结果与历史引用，
 * 供工具决策、总结和恢复流程读取最近一次工具上下文。
 */
public interface ToolStateStore {
    ToolState load(String sessionId);

    void save(String sessionId, ToolState state);
}
