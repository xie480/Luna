package org.yilena.luna.rag.pipelines;

import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RoutePlan;

/**
 * 该流水线接口定义不同检索路由的统一执行约束，便于编排层按 route 分发到具体实现。
 */
public interface RetrievalPipeline {

    /**
     * 返回当前流水线对应的检索路由。
     */
    RetrievalRoute route();

    /**
     * 执行当前路由的完整检索流程，并返回标准化检索结果。
     */
    RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request);
}
