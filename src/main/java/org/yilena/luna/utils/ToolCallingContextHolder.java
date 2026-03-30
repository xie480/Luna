package org.yilena.luna.utils;

import org.yilena.luna.entity.ToolCallingContext;

/**
 * Tool Calling 上下文 ThreadLocal 持有器
 */
public final class ToolCallingContextHolder {

    private static final ThreadLocal<ToolCallingContext> HOLDER = new ThreadLocal<>(); // 定义方法签名

    private ToolCallingContextHolder() { // 定义方法签名
    } // 结束当前代码块

    public static void set(ToolCallingContext context) { // 定义方法签名
        HOLDER.set(context); // 执行语句逻辑
    } // 结束当前代码块

    public static ToolCallingContext get() { // 定义方法签名
        return HOLDER.get(); // 返回处理结果
    } // 结束当前代码块

    public static void clear() { // 定义方法签名
        HOLDER.remove(); // 执行语句逻辑
    } // 结束当前代码块
} // 结束当前代码块
