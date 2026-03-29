package org.yilena.luna.rag.rankers;

import org.springframework.stereotype.Component;
import org.yilena.luna.rag.models.Evidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 证据去重器，按来源与归一化内容消除重复证据。 */
@Component
public class EvidenceDeduplicator {

    public List<Evidence> deduplicate(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<Evidence> result = new ArrayList<>();
        for (Evidence evidence : evidences) {
            String key = evidence.getSource().value() + "::" + normalize(evidence.getContent());
            if (seen.add(key)) {
                result.add(evidence);
            }
        }
        return result;
    }

    private String normalize(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.replaceAll("\\s+", " ").trim().toLowerCase();
        if (cleaned.length() > 240) {
            return cleaned.substring(0, 240);
        }
        return cleaned;
    }
}
