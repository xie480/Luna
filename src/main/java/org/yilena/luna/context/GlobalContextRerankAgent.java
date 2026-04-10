package org.yilena.luna.context;

import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.rag.models.RetrievalResponse;

import java.util.List;
import java.util.Map;

/**
 * 全局上下文重排代理接口，负责对知识、记忆和能力候选做统一筛选与排序。
 */
public interface GlobalContextRerankAgent {
    /**
     * 根据当前任务上下文输出重排后的精选候选结果。
     */
    ContextRerankResult rerank(InputReconstructionResult reconstructionResult,
                               StructuredContextPackage contextPackage,
                               RetrievalResponse retrievalResponse,
                               List<Map<String, Object>> capabilityCandidates,
                               TaskRuntimeState taskState);
}
