package org.yilena.luna.rag.rankers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.rag.config.RagProperties;
import org.yilena.luna.rag.models.Evidence;

import java.util.List;

/** 证据压缩器，按配置限制证据内容长度以控制上下文体积。 */
@Component
@RequiredArgsConstructor
public class EvidenceCompressor {

    private final RagProperties ragProperties;

    public List<Evidence> compress(List<Evidence> evidences) {
        int maxChars = ragProperties.getCompressionMaxChars();
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
