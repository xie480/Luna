package org.yilena.luna.enums;

/**
 * 模型类型枚举，用于区分系统接入的大语言模型提供方。
 */
public enum ModelType {
    /**
     * OpenAI 兼容接口模型。
     */
    OPENAI_COMPATIBLE,
    /**
     * 通义千问模型。
     */
    QWEN,
    /**
     * Ollama 本地模型。
     */
    OLLAMA,
    /**
     * Gemini 模型。
     */
    GEMINI
}
