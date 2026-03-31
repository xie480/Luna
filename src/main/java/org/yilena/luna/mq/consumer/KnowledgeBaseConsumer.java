package org.yilena.luna.mq.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.mapper.KnowledgeBaseMapper;
import org.yilena.luna.mq.dto.KnowledgeBaseMessage;
import org.yilena.luna.rag.TextSplitter;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_KB_ADD, consumerGroup = RocketMqConstant.GROUP_KB_ADD)
public class KnowledgeBaseConsumer implements RocketMQListener<KnowledgeBaseMessage> {

    private final LlmClientUtil llmClientUtil;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public void onMessage(KnowledgeBaseMessage msg) {
        String title = msg.getTitle();
        String content = msg.getContent();
        String rawSourceType = msg.getSourceType();
        String sourcePath = msg.getSourcePath();

        SourceType sourceType = parseSourceType(rawSourceType);
        if (sourceType == null) {
            log.error("invalid source type, title={}, sourceType={}", title, rawSourceType);
            return;
        }

        List<String> chunks = TextSplitter.splitText(content, 500, 50);
        Long docId = null;
        try {
            docId = knowledgeBaseMapper.insertKnowledgeDocument(title, sourceType.name(), sourcePath);
        } catch (Exception e) {
            log.error("create knowledge document failed: {}", e.getMessage());
        }
        if (docId == null) {
            return;
        }

        int successCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            try {
                String chunk = chunks.get(i);
                String vectorStr = llmClientUtil.getEmbedding(chunk);
                if (vectorStr == null || vectorStr.trim().isEmpty() || "[]".equals(vectorStr.trim())) {
                    continue;
                }
                knowledgeBaseMapper.insertKnowledgeChunk(docId, i + 1, chunk, null, "[]", vectorStr);
                successCount++;
            } catch (Exception e) {
                log.error("insert chunk failed: {}", e.getMessage());
            }
        }
        log.info("knowledge ingested, successChunks={}/{}", successCount, chunks.size());
    }

    private SourceType parseSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return SourceType.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            if ("0".equals(v)) {
                return SourceType.FILE;
            }
            if ("1".equals(v)) {
                return SourceType.WEB_SEARCH;
            }
            if ("2".equals(v)) {
                return SourceType.MANUAL_INPUT;
            }
            return null;
        }
    }
}
