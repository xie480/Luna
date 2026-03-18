package org.yilena.luna.adapter;

/**
 * LLM 適配接口
 * 隔離具體模型實現
 */
public interface LlmAdapter {
    /**
     * 生成回復
     * @param prompt 提示詞
     * @return 模型生成的文本
     */
    String generate(String prompt);
}
