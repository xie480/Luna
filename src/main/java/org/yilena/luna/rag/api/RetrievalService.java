package org.yilena.luna.rag.api;

import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;

/**
 * 该服务接口定义统一的 RAG 检索入口，屏蔽路由、pipeline 与 retriever 的内部细节。
 */
public interface RetrievalService {

    /**
     * 执行一次完整的检索流程，并返回标准化的结构化检索结果。
     */
    RetrievalResponse retrieve(RetrievalRequest request);
}
