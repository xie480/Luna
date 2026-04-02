package org.yilena.luna.context.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.context.EvidenceBlockBuilder;
import org.yilena.luna.rag.models.Evidence;

import java.util.ArrayList;
import java.util.List;

@Service
public class DefaultEvidenceBlockBuilder implements EvidenceBlockBuilder {
    @Override
    public List<String> buildKnowledgeBlocks(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
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
            out.add("evidence#" + index + " title: " + title + "\ncontent: " + content);
            index++;
        }
        return out;
    }
}

