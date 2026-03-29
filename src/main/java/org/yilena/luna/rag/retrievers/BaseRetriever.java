package org.yilena.luna.rag.retrievers;

import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.List;
import java.util.Map;

/** 检索器统一抽象，每个实现负责一个 RetrievalSource 的召回。 */
public interface BaseRetriever {
    RetrievalSource source();

    List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters);
}
