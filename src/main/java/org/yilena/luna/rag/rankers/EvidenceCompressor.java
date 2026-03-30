package org.yilena.luna.rag.rankers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.Evidence;

import java.util.List;

/** Evidence compressor with configurable max chars. */
@Component
@RequiredArgsConstructor
public class EvidenceCompressor {

    private final RagProperties ragProperties;

    public List<Evidence> compress(List<Evidence> evidences) {
        return compress(evidences, ragProperties.getCompressionMaxChars());
    }

    public List<Evidence> compress(List<Evidence> evidences, int maxChars) {
        return evidences.stream()
                .map(item -> item.toBuilder().content(truncate(item.getContent(), maxChars)).build())
                .toList();
    }

    private String truncate(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "...";
    }
}
