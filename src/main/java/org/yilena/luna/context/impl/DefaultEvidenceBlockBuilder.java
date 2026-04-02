package org.yilena.luna.context.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.context.EvidenceBlockBuilder;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.rag.models.Evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultEvidenceBlockBuilder implements EvidenceBlockBuilder {
    @Override
    public List<EvidenceBlock> buildKnowledgeBlocks(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        List<EvidenceBlock> out = new ArrayList<>();
        int index = 1;
        for (Evidence evidence : evidences) {
            if (evidence == null) {
                continue;
            }
            String title = evidence.getTitle() == null ? "" : evidence.getTitle();
            String content = evidence.getContent() == null ? "" : evidence.getContent();
            if (title.isBlank() && content.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("rawId", evidence.getId());
            metadata.put("source", evidence.getSource() == null ? "" : evidence.getSource().value());
            metadata.put("type", evidence.getType() == null ? "" : evidence.getType());
            metadata.put("role", evidence.getRole() == null ? "" : evidence.getRole().value());
            metadata.put("rawMetadata", evidence.getMetadata() == null ? Map.of() : evidence.getMetadata());
            out.add(EvidenceBlock.builder()
                    .blockId("evidence#" + index)
                    .sourceType("knowledge")
                    .title(title)
                    .content(content)
                    .score(evidence.getScore())
                    .metadata(metadata)
                    .build());
            index++;
        }
        return out;
    }
}
