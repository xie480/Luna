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

/**
 * 知识库消费者，负责消费异步知识入库消息并完成文档切片和向量化写入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_KB_ADD, consumerGroup = RocketMqConstant.GROUP_KB_ADD)
public class KnowledgeBaseConsumer implements RocketMQListener<KnowledgeBaseMessage> {

    /**
     * 大模型客户端工具，用于生成知识切片向量。
     */
    private final LlmClientUtil llmClientUtil;

    /**
     * 知识库数据访问对象，用于插入文档和切片记录。
     */
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public void onMessage(KnowledgeBaseMessage msg) {
        String title = msg.getTitle();
        String content = msg.getContent();
        String rawSourceType = msg.getSourceType();
        String sourcePath = msg.getSourcePath();

        /**
         * 先解析来源类型，来源非法时直接终止入库，避免生成无法归类的知识数据。
         */
        SourceType sourceType = parseSourceType(rawSourceType);
        if (sourceType == null) {
            log.error("invalid source type, title={}, sourceType={}", title, rawSourceType);
            return;
        }

        /**
         * 将长文本切分为知识片段，并先创建文档主记录作为切片归属。
         */
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

        /**
         * 对每个片段生成向量并写入知识切片表，生成失败的片段仅跳过不影响整体消费。
         */
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

    /**
     * 解析知识来源类型，兼容枚举名和历史数字编码。
     */
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
