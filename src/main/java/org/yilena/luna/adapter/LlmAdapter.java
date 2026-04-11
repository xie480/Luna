package org.yilena.luna.adapter;

/**
 * LLM 适配器接口，负责抽象不同大模型服务的统一文本生成能力。
 */
public interface LlmAdapter {

    /**
     * 根据提示词生成文本结果。
     */
    String generate(String prompt);
}
