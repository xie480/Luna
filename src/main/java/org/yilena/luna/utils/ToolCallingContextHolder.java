package org.yilena.luna.utils;

import org.yilena.luna.entity.ToolCallingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tool Calling 上下文 ThreadLocal 持有器
 */
public final class ToolCallingContextHolder {

    private static final InheritableThreadLocal<ToolCallingContext> HOLDER = new InheritableThreadLocal<>();

    private ToolCallingContextHolder() {
    }

    public static void set(ToolCallingContext context) {
        HOLDER.set(context);
    }

    public static ToolCallingContext get() {
        return HOLDER.get();
    }

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

    public static List<Map<String, Object>> snapshotToolExecutionTraces() {
        ToolCallingContext context = HOLDER.get();
        if (context == null || context.getToolExecutionTraces() == null) {
            return List.of();
        }
        return new ArrayList<>(context.getToolExecutionTraces());
    }

    public static void clear() {
        HOLDER.remove();
    }
}
