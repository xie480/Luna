package org.yilena.luna.context.impl;

import org.junit.jupiter.api.Test;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultEvidenceBlockBuilderTest {

    @Test
    void shouldUseRawEvidenceIdAsStableBlockId() {
        DefaultEvidenceBlockBuilder builder = new DefaultEvidenceBlockBuilder();
        Evidence evidence = Evidence.builder()
                .id("knowledge:101")
                .source(RetrievalSource.KNOWLEDGE)
                .type("knowledge")
                .title("title")
                .content("content")
                .score(0.91)
                .build();

        List<EvidenceBlock> blocks = builder.buildKnowledgeBlocks(List.of(evidence));

        assertEquals(1, blocks.size());
        assertEquals("knowledge:101", blocks.get(0).getBlockId());
        assertEquals("knowledge:101", blocks.get(0).getMetadata().get("rawId"));
    }

    @Test
    void shouldGenerateDeterministicAutoBlockIdAndKeepDuplicateSuffixTraceable() {
        DefaultEvidenceBlockBuilder builder = new DefaultEvidenceBlockBuilder();
        Evidence noIdEvidence = Evidence.builder()
                .source(RetrievalSource.KNOWLEDGE)
                .type("knowledge")
                .title("same-title")
                .content("same-content")
                .score(0.88)
                .build();

        List<EvidenceBlock> firstRun = builder.buildKnowledgeBlocks(List.of(noIdEvidence, noIdEvidence));
        List<EvidenceBlock> secondRun = builder.buildKnowledgeBlocks(List.of(noIdEvidence));

        assertEquals(2, firstRun.size());
        assertTrue(firstRun.get(0).getBlockId().startsWith("evidence:auto:"));
        assertEquals(firstRun.get(0).getBlockId() + "#1", firstRun.get(1).getBlockId());
        assertEquals(firstRun.get(0).getBlockId(), secondRun.get(0).getBlockId());
        assertNotEquals(firstRun.get(0).getBlockId(), firstRun.get(1).getBlockId());
    }
}
