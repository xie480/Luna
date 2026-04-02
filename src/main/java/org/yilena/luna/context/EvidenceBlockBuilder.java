package org.yilena.luna.context;

import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.context.model.EvidenceBlock;

import java.util.List;

public interface EvidenceBlockBuilder {
    List<EvidenceBlock> buildKnowledgeBlocks(List<Evidence> evidences);
}
