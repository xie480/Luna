package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.mapper.RuntimeReadMapper;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.RuntimeRetriever;
import org.yilena.luna.properties.RuntimeAuditReplayProperty;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
/**
 * 基于 JDBC 的运行态检索器，负责加载会话运行数据、最近消息、工具轨迹和上下文快照，
 * 为上下文编译提供原始运行时素材。
 */
public class JdbcRuntimeRetriever implements RuntimeRetriever {

    private final RuntimeReadMapper runtimeReadMapper;
    private final MemoryHotLayerService memoryHotLayerService;
    private final RuntimeAuditReplayProperty runtimeAuditReplayProperty;

    @Override
    /**
     * 优先从热层读取运行态缓存，未命中时再回源数据库组装结果。
     */
    public Map<String, Object> retrieve(String sessionId) {
        Map<String, Object> cached = memoryHotLayerService.getSessionCache(sessionId);
        if (!cached.isEmpty()) {
            Map<String, Object> result = new HashMap<>(cached);
            result.put("pending_tool_call", memoryHotLayerService.getLatestPendingToolCall(sessionId));
            return result;
        }

        Map<String, Object> result = new HashMap<>();
        /**
         * 回源加载运行会话、最近消息、工具结果和上下文快照，
         * 并补充当前待处理工具调用信息。
         */
        result.put("session", queryOne(() -> runtimeReadMapper.selectRuntimeSession(sessionId)));
        result.put("recent_messages", queryList(() -> runtimeReadMapper.selectRuntimeRecentMessages(sessionId)));
        result.put("active_tool_results", queryList(() -> queryToolResults(sessionId)));
        result.put("context_snapshots", queryList(() -> queryContextSnapshots(sessionId)));
        result.put("pending_tool_call", memoryHotLayerService.getLatestPendingToolCall(sessionId));
        memoryHotLayerService.putSessionCache(sessionId, result);
        return result;
    }

    private List<Map<String, Object>> queryToolResults(String sessionId) {
        /**
         * 根据回放配置选择全量轨迹或窗口轨迹，
         * 兼顾审计完整性与上下文读取成本。
         */
        if (runtimeAuditReplayProperty.fullReplayMode()) {
            return runtimeReadMapper.selectRuntimeToolResultsFull(sessionId);
        }
        return runtimeReadMapper.selectRuntimeToolResultsWindow(
                sessionId,
                runtimeAuditReplayProperty.safeToolResultsWindowLimit()
        );
    }

    private List<Map<String, Object>> queryContextSnapshots(String sessionId) {
        if (runtimeAuditReplayProperty.fullReplayMode()) {
            return runtimeReadMapper.selectRuntimeContextSnapshotsFull(sessionId);
        }
        return runtimeReadMapper.selectRuntimeContextSnapshotsWindow(
                sessionId,
                runtimeAuditReplayProperty.safeContextSnapshotsWindowLimit()
        );
    }

    private Map<String, Object> queryOne(SqlOneSupplier supplier) {
        try {
            Map<String, Object> row = supplier.get();
            return row == null ? Collections.emptyMap() : row;
        } catch (Exception ignore) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> queryList(SqlListSupplier supplier) {
        try {
            List<Map<String, Object>> rows = supplier.get();
            return rows == null ? Collections.emptyList() : rows;
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    /**
     * 单行 SQL 查询供应器，用于延迟执行并返回一条运行态记录。
     */
    @FunctionalInterface
    private interface SqlOneSupplier {
        Map<String, Object> get();
    }

    /**
     * 列表 SQL 查询供应器，用于延迟执行并返回多条运行态记录。
     */
    @FunctionalInterface
    private interface SqlListSupplier {
        List<Map<String, Object>> get();
    }
}
