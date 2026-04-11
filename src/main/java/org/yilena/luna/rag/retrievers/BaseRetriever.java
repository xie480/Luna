package org.yilena.luna.rag.retrievers;

import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.QueryObject;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.List;
import java.util.Map;

/**
 * 该检索器接口用于统一不同 RetrievalSource 的召回实现，约束每个来源都能输出标准化 Evidence。
 */
public interface BaseRetriever {

    /**
     * 返回当前检索器负责的数据源类型。
     */
    RetrievalSource source();

    /**
     * 根据查询对象、topK 和过滤条件执行召回，输出标准化证据列表。
     */
    List<Evidence> retrieve(QueryObject queryObject, int topK, Map<String, Object> filters);
}
