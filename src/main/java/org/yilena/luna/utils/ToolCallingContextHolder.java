package org.yilena.luna.utils;

import org.yilena.luna.entity.ToolCallingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 该线程上下文持有器用于在工具调用链路中保存 ToolCallingContext，并追加执行痕迹供后续审计和回放。
 */
public final class ToolCallingContextHolder {

    /**
     * 可被子线程继承的工具调用上下文。
     */
    private static final InheritableThreadLocal<ToolCallingContext> HOLDER = new InheritableThreadLocal<>();

    private ToolCallingContextHolder() {
    }

    /**
     * 绑定当前线程的工具调用上下文。
     */
    public static void set(ToolCallingContext context) {
        HOLDER.set(context);
    }

    /**
     * 获取当前线程的工具调用上下文。
     */
    public static ToolCallingContext get() {
        return HOLDER.get();
    }

    /**
     * 向当前上下文追加一次工具执行痕迹，供后续审计和状态快照复用。
     */
    public static void appendToolExecutionTrace(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return;
        }
        ToolCallingContext context = HOLDER.get();
        if (context == null) {
            return;
        }
        List<Map<String, Object>> traces = context.getToolExecutionTraces();
        if (traces == null) {
            traces = new CopyOnWriteArrayList<>();
            context.setToolExecutionTraces(traces);
        }
        traces.add(trace);
    }

    /**
     * 获取当前线程中工具执行痕迹的快照副本，避免外部直接修改原始上下文数据。
     */
    public static List<Map<String, Object>> snapshotToolExecutionTraces() {
        ToolCallingContext context = HOLDER.get();
        if (context == null || context.getToolExecutionTraces() == null) {
            return List.of();
        }
        return new ArrayList<>(context.getToolExecutionTraces());
    }

    /**
     * 清理当前线程中的工具调用上下文。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
