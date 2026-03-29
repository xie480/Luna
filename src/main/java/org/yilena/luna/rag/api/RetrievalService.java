package org.yilena.luna.rag.api;

import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;

/**
 * RAG 对外统一服务接口。
 * 目标是屏蔽内部路由、pipeline、retriever 实现细节，
 * 让上层只依赖一个稳定入口。
 */
public interface RetrievalService {
    /**
     * 执行一次完整检索流程并返回结构化结果。
     *
     * @param request 检索请求（query/session/sourceScope/route 限制等）
     * @return 标准化检索响应
     */
    RetrievalResponse retrieve(RetrievalRequest request);
}
