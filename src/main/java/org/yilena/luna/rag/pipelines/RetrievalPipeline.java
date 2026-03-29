package org.yilena.luna.rag.pipelines;

import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RoutePlan;

/** 检索流水线统一接口，供编排层按 route 分发执行。 */
public interface RetrievalPipeline {
    RetrievalRoute route();

    RetrievalResponse execute(QueryObject queryObject, RoutePlan plan, RetrievalRequest request);
}
