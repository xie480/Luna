package org.yilena.luna.constants;

public final class LlmConstant {

    public static final long INFERENCE_HTTP_TIMEOUT_MS = 1500L;
    public static final double DEFAULT_TEMPERATURE = 0.7D;
    public static final double TASK_TEMPERATURE = 0.2D;
    public static final double ZERO_TEMPERATURE = 0.0D;
    public static final long CHAT_TIMEOUT_SECONDS = 120L;
    public static final int CHAT_MAX_RETRIES = 3;

    private LlmConstant() {
    }
}
