package org.yilena.luna.mq.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.mq.dto.KnowledgeBaseMessage;
import org.yilena.luna.rag.TextSplitter;
import org.yilena.luna.service.KnowledgeBaseService;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_KB_ADD, consumerGroup = RocketMqConstant.GROUP_KB_ADD)
/**
 * KnowledgeBaseConsumer ??
 */
public class KnowledgeBaseConsumer implements RocketMQListener<KnowledgeBaseMessage> {

    private final LlmClientUtil llmClientUtil;
    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public void onMessage(KnowledgeBaseMessage msg) {
        String title = msg.getTitle();
        String content = msg.getContent();
        String rawSourceType = msg.getSourceType();
        String sourcePath = msg.getSourcePath();

        SourceType sourceType = parseSourceType(rawSourceType);
        if (sourceType == null) {
            log.error("MQ 消費: sourceType 非法，消息丟棄。title={}, sourceType={}", title, rawSourceType);
            return;
        }

        // 1. 文本分片 (每段 500 字，重疊 50 字)
        List<String> chunks = TextSplitter.splitText(content, 500, 50);
        log.info("MQ 消費: 開始處理知識庫寫入，標題: {}, sourceType={}, 總分片數: {}", title, sourceType.getValue(), chunks.size());

        int successCount = 0;
        for (String chunk : chunks) {
            try {
                // 2. 調用大模型獲取 Embedding 向量 (耗時操作)
                String vectorStr = llmClientUtil.getEmbedding(chunk);

                if (vectorStr != null && !vectorStr.trim().isEmpty() && !vectorStr.trim().equals("[]")) {
                    // 3. 構建實體並保存
                    KnowledgeBase kb = KnowledgeBase.builder()
                            .title(title)
                            .content(chunk)
                            .sourceType(sourceType)
                            .sourcePath(sourcePath)
                            .embedding(vectorStr)
                            .build();

                    knowledgeBaseService.save(kb);
                    successCount++;
                } else {
                    log.warn("分片向量化失敗，跳過該分片。標題: {}", title);
                }
            } catch (Exception e) {
                log.error("寫入知識庫分片異常: {}", e.getMessage(), e);
            }
        }
        log.info("MQ 消費: 知識庫寫入完成，成功寫入分片數: {}/{}", successCount, chunks.size());
    }

    /**
     * 兼容解析 SourceType：
     * - 新格式：FILE / WEB_SEARCH / MANUAL_INPUT
     * - 舊格式：0 / 1 / 2
     */
    private SourceType parseSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String v = raw.trim();
        try {
            // 先按枚舉值解析
            return SourceType.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            // 再按舊 code 解析
            if ("0".equals(v)) return SourceType.FILE;
            if ("1".equals(v)) return SourceType.WEB_SEARCH;
            if ("2".equals(v)) return SourceType.MANUAL_INPUT;
            return null;
        }
    }
}
