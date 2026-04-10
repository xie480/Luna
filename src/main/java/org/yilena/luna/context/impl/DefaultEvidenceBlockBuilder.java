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
/**
 * 知识证据块构建器默认实现，负责将检索证据转换为统一的上下文证据块，
 * 便于后续重排、裁剪和提示词组装阶段直接消费。
 */
public class DefaultEvidenceBlockBuilder implements EvidenceBlockBuilder {
    @Override
    /**
     * 将原始知识检索结果整理为带稳定标识的证据块集合。
     */
    public List<EvidenceBlock> buildKnowledgeBlocks(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        List<EvidenceBlock> out = new ArrayList<>();
        Map<String, Integer> blockIdCounter = new LinkedHashMap<>();
        /**
         * 逐条过滤空证据、补齐元数据并生成稳定块编号，
         * 确保后续链路能够可靠引用和追踪同一份知识片段。
         */
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
        /**
         * 优先复用检索系统给出的证据主键，
         * 若缺失则根据来源和内容生成可重复计算的回退编号。
         */
        if (evidence != null && evidence.getId() != null && !evidence.getId().isBlank()) {
            return evidence.getId().trim();
        }
        String source = evidence == null || evidence.getSource() == null ? "" : evidence.getSource().value();
        String role = evidence == null || evidence.getRole() == null ? "" : evidence.getRole().value();
        int digest = Math.abs(Objects.hash(source, role, title == null ? "" : title, content == null ? "" : content));
        return "evidence:auto:" + Integer.toHexString(digest);
    }
}
