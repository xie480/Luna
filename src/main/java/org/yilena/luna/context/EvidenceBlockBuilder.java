package org.yilena.luna.context;

import org.yilena.luna.rag.models.Evidence;

import java.util.List;

public interface EvidenceBlockBuilder {
    List<String> buildKnowledgeBlocks(List<Evidence> evidences);
}

