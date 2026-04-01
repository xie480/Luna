package org.yilena.luna.rag.rankers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.support.SemanticTextService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Evidence compressor with semantic merge and extractive summarization. */
@Component
@RequiredArgsConstructor
public class EvidenceCompressor {

    private final RagProperties ragProperties;
    private final SemanticTextService semanticTextService;

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
        List<Evidence> merged = new ArrayList<>();
        Map<String, List<Double>> embeddingCache = new HashMap<>();
        for (Evidence evidence : evidences) {
            int matched = -1;
            double bestSimilarity = 0.0;
            for (int i = 0; i < merged.size(); i++) {
                Evidence existing = merged.get(i);
                double similarity = semanticTextService.similarity(
                        existing.getContent(),
                        evidence.getContent(),
                        embeddingCache
                );
                if (similarity >= ragProperties.getCompressionSemanticMergeThreshold()) {
                    matched = i;
                    bestSimilarity = similarity;
                    break;
                }
            }
            if (matched < 0) {
                merged.add(evidence);
                continue;
            }
            Evidence existing = merged.get(matched);
            String mergedContent = mergeContent(existing.getContent(), evidence.getContent());
            Map<String, Object> metadata = existing.getMetadata() == null
                    ? new HashMap<>()
                    : new HashMap<>(existing.getMetadata());
            metadata.put("merged", true);
            metadata.put("semantic_merge_similarity", bestSimilarity);
            metadata.put("merged_count", ((Number) metadata.getOrDefault("merged_count", 1)).intValue() + 1);
            List<String> mergedIds = toStringList(metadata.get("merged_evidence_ids"));
            mergedIds.add(evidence.getId());
            metadata.put("merged_evidence_ids", mergedIds);
            merged.set(matched, existing.toBuilder()
                    .content(mergedContent)
                    .metadata(Collections.unmodifiableMap(metadata))
                    .build());
        }
        return merged;
    }

    private List<String> toStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        return new ArrayList<>();
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
        String summarized = summarize(content, ragProperties.getCompressionSummarySentences(), maxChars);
        if (summarized.length() <= maxChars) {
            return summarized;
        }
        return truncate(summarized, maxChars);
    }

    private String summarize(String content, int sentenceCount, int maxChars) {
        int keep = Math.max(1, sentenceCount);
        Map<String, List<Double>> embeddingCache = new HashMap<>();
        String semanticSummary = semanticTextService.summarizeBySemantic(content, keep, maxChars, embeddingCache);
        if (semanticSummary != null && !semanticSummary.isBlank()) {
            return semanticSummary;
        }
        List<String> fallback = semanticTextService.splitSentences(content).stream().limit(keep).toList();
        if (fallback.isEmpty()) {
            return content;
        }
        return String.join(" ", fallback);
    }

    private String truncate(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "...";
    }
}
