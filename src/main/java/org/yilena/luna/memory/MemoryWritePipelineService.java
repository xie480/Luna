package org.yilena.luna.memory;

import org.yilena.luna.memory.model.StructuredContextPackage;

/**
 * 记忆写入流水线接口，负责在单轮对话完成后把用户输入、回复和上下文快照写回各层记忆介质，
 * 保证后续轮次能够复用本轮沉淀的信息。
 */
public interface MemoryWritePipelineService {
    void writeAfterTurn(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage);
}
