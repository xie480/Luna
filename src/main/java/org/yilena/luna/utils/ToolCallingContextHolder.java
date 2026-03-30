package org.yilena.luna.utils;

import org.yilena.luna.entity.ToolCallingContext;

/**
 * Tool Calling 上下文 ThreadLocal 持有器
 */
public final class ToolCallingContextHolder {

    private static final ThreadLocal<ToolCallingContext> HOLDER = new ThreadLocal<>();

    private ToolCallingContextHolder() {
    }

    public static void set(ToolCallingContext context) {
        HOLDER.set(context);
    }

    public static ToolCallingContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
