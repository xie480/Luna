package org.yilena.luna.context;

import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.context.model.EvidenceBlock;

import java.util.List;

/**
 * 证据块构建器接口，负责把检索证据转换为统一的上下文证据块结构。
 */
public interface EvidenceBlockBuilder {
    /**
     * 将知识检索结果构造成标准证据块集合。
     */
    List<EvidenceBlock> buildKnowledgeBlocks(List<Evidence> evidences);
}
