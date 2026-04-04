package org.yilena.luna.context.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.context.EvidenceBlockBuilder;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.rag.models.Evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DefaultEvidenceBlockBuilder implements EvidenceBlockBuilder {
    @Override
    public List<EvidenceBlock> buildKnowledgeBlocks(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        List<EvidenceBlock> out = new ArrayList<>();
        Map<String, Integer> blockIdCounter = new LinkedHashMap<>();
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
            String baseBlockId = resolveStableBlockId(evidence, title, content);
            int seen = blockIdCounter.getOrDefault(baseBlockId, 0);
            blockIdCounter.put(baseBlockId, seen + 1);
            String blockId = seen == 0 ? baseBlockId : baseBlockId + "#" + seen;
            metadata.put("traceable_block_id", blockId);
            out.add(EvidenceBlock.builder()
                    .blockId(blockId)
                    .sourceType("knowledge")
                    .title(title)
                    .content(content)
                    .score(evidence.getScore())
                    .metadata(metadata)
                    .build());
        }
        return out;
    }

    private String resolveStableBlockId(Evidence evidence, String title, String content) {
        if (evidence != null && evidence.getId() != null && !evidence.getId().isBlank()) {
            return evidence.getId().trim();
        }
        String source = evidence == null || evidence.getSource() == null ? "" : evidence.getSource().value();
        String role = evidence == null || evidence.getRole() == null ? "" : evidence.getRole().value();
        int digest = Math.abs(Objects.hash(source, role, title == null ? "" : title, content == null ? "" : content));
        return "evidence:auto:" + Integer.toHexString(digest);
    }
}
