package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.Map;

/**
 * 该模型用于标准化不同检索来源返回的证据内容，屏蔽底层数据结构差异并统一供上层消费。
 */
@Value
@Builder(toBuilder = true)
public class Evidence {
    /**
     * 统一证据标识，例如 knowledge:101。
     */
    String id;
    /**
     * 证据来源类型。
     */
    RetrievalSource source;
    /**
     * 证据类别字符串，例如 knowledge、memory、preference。
     */
    String type;
    /**
     * 证据在语义层面的角色分类。
     */
    @Builder.Default
    EvidenceRole role = EvidenceRole.FACT;
    /**
     * 证据标题，可为空。
     */
    String title;
    /**
     * 证据正文内容。
     */
    String content;
    /**
     * 当前检索管线内部计算出的排序分数。
     */
    double score;
    /**
     * 来源特定的扩展元数据。
     */
    @Builder.Default
    Map<String, Object> metadata = Collections.emptyMap();
}
