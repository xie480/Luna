package org.yilena.luna.rag.rankers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.Evidence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Evidence compressor with configurable max chars. */
@Component
@RequiredArgsConstructor
public class EvidenceCompressor {

    private final RagProperties ragProperties;

    public List<Evidence> compress(List<Evidence> evidences) {
        return compress(evidences, ragProperties.getCompressionMaxChars());
    }

    public List<Evidence> compress(List<Evidence> evidences, int maxChars) {
        List<Evidence> merged = mergeSimilar(evidences);
        return merged.stream()
                .map(item -> item.toBuilder().content(compressContent(item.getContent(), maxChars)).build())
                .toList();
    }

    private List<Evidence> mergeSimilar(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        Map<String, Evidence> merged = new HashMap<>();
        int threshold = Math.max(32, ragProperties.getCompressionMergeSimilarityChars());
        for (Evidence evidence : evidences) {
            String key = normalizeKey(evidence.getContent(), threshold);
            Evidence existing = merged.get(key);
            if (existing == null) {
                merged.put(key, evidence);
                continue;
            }
            String mergedContent = mergeContent(existing.getContent(), evidence.getContent());
            Map<String, Object> metadata = new HashMap<>(existing.getMetadata());
            metadata.put("merged", true);
            metadata.put("merged_count", ((Number) metadata.getOrDefault("merged_count", 1)).intValue() + 1);
            merged.put(key, existing.toBuilder().content(mergedContent).metadata(metadata).build());
        }
        return new ArrayList<>(merged.values());
    }

    private String normalizeKey(String content, int threshold) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim().toLowerCase();
        if (normalized.length() <= threshold) {
            return normalized;
        }
        return normalized.substring(0, threshold);
    }

    private String mergeContent(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        if (left.contains(right)) {
            return left;
        }
        if (right.contains(left)) {
            return right;
        }
        return left + " | " + right;
    }

    private String compressContent(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        String summarized = summarize(content, ragProperties.getCompressionSummarySentences());
        if (summarized.length() <= maxChars) {
            return summarized;
        }
        return truncate(summarized, maxChars);
    }

    private String summarize(String content, int sentenceCount) {
        int keep = Math.max(1, sentenceCount);
        String[] parts = content.split("(?<=[。！？!?\\.])");
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(trimmed);
            added++;
            if (added >= keep) {
                break;
            }
        }
        return builder.isEmpty() ? content : builder.toString();
    }

    private String truncate(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "...";
    }
}
