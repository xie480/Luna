package org.yilena.luna.constants;

/**
 * 大模型常量类，集中定义推理超时、温度和重试次数等基础参数。
 */
public final class LlmConstant {

    /**
     * 推理 HTTP 请求默认超时，单位为毫秒。
     */
    public static final long INFERENCE_HTTP_TIMEOUT_MS = 1500L;

    /**
     * 通用对话默认温度。
     */
    public static final double DEFAULT_TEMPERATURE = 0.7D;

    /**
     * 任务型推理默认温度，偏向稳定输出。
     */
    public static final double TASK_TEMPERATURE = 0.2D;

    /**
     * 零温度参数，适用于需要确定性输出的场景。
     */
    public static final double ZERO_TEMPERATURE = 0.0D;

    /**
     * 聊天请求超时，单位为秒。
     */
    public static final long CHAT_TIMEOUT_SECONDS = 120L;

    /**
     * 聊天请求最大重试次数。
     */
    public static final int CHAT_MAX_RETRIES = 3;

    private LlmConstant() {
    }
}
