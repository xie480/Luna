package org.yilena.luna.state.store;

import org.yilena.luna.state.model.TaskState;

/**
 * 任务状态存储接口，负责持久化任务目标、阶段推进和槽位确认状态，
 * 为编排器和执行器共享任务主状态提供统一入口。
 */
public interface TaskStateStore {
    TaskState load(String sessionId);

    void save(String sessionId, TaskState state);
}
