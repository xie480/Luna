package org.yilena.luna.memory.model;

import lombok.Builder;
import lombok.Value;

/**
 * 该模型用于描述上下文编译阶段的装载策略，控制预加载深度和降级开关。
 */
@Value
@Builder
public class ContextCompileOptions {

    /**
     * 预加载模式，决定上下文编译时拉取的数据范围。
     */
    PreloadMode preloadMode;
    /**
     * 当前编译针对的节点类型。
     */
    String nodeType;
    /**
     * 是否允许在主策略不足时启用兜底预加载。
     */
    Boolean fallbackPreloadEnabled;

    /**
     * 创建自动模式配置，由运行时根据场景决定预加载深度。
     */
    public static ContextCompileOptions auto() {
        return ContextCompileOptions.builder()
                .preloadMode(PreloadMode.AUTO)
                .fallbackPreloadEnabled(null)
                .build();
    }

    /**
     * 创建最小化模式配置，尽量减少上下文装载量。
     */
    public static ContextCompileOptions minimal() {
        return ContextCompileOptions.builder()
                .preloadMode(PreloadMode.MINIMAL)
                .fallbackPreloadEnabled(false)
                .build();
    }

    /**
     * 创建完整模式配置，优先加载更全面的上下文信息。
     */
    public static ContextCompileOptions full() {
        return ContextCompileOptions.builder()
                .preloadMode(PreloadMode.FULL)
                .fallbackPreloadEnabled(true)
                .build();
    }

    /**
     * 该枚举用于定义上下文编译的预加载策略级别。
     */
    public enum PreloadMode {
        /**
         * 由系统根据场景自动决定预加载范围。
         */
        AUTO,
        /**
         * 仅加载最必要的上下文数据。
         */
        MINIMAL,
        /**
         * 尽可能加载完整上下文数据。
         */
        FULL
    }
}
