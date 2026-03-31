package org.yilena.luna.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.entity.KnowledgeChunkRecord;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.mapper.KnowledgeBaseMapper;
import org.yilena.luna.mq.dto.KnowledgeBaseMessage;
import org.yilena.luna.service.KnowledgeBaseService;
import org.yilena.luna.utils.LlmClientUtil;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 知識庫服務實現類
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final LlmClientUtil llmClientUtil;
    private final RocketMQTemplate rocketMQTemplate;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public void addKnowledge(String title, String content, SourceType sourceType, String sourcePath) {
        // 向後兼容：SourceType 可能是 code 值枚舉序列化，不要再用 name()
        // 這裡統一傳遞 value（FILE / WEB_SEARCH / MANUAL_INPUT）
        KnowledgeBaseMessage msg = KnowledgeBaseMessage.builder()
                .title(title)
                .content(content)
                .sourceType(sourceType != null ? sourceType.getValue() : null)
                .sourcePath(sourcePath)
                .build();

        rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_KB_ADD, msg);
        log.info("已發送知識庫寫入請求至 MQ, 標題: {}, sourceType={}", title, msg.getSourceType());
    }

    @Override
    public List<KnowledgeChunkRecord> searchKnowledge(String query, int topK) {
        try {
            // 檢索操作需要實時返回，無法異步，仍保持同步調用
            String queryVectorStr = llmClientUtil.getEmbedding(query);

            if (queryVectorStr == null || queryVectorStr.trim().isEmpty() || queryVectorStr.trim().equals("[]")) {
                log.warn("查詢問題向量化失敗，無法進行檢索: {}", query);
                return Collections.emptyList();
            }

            log.debug("開始向量檢索，TopK: {}", topK);
            return knowledgeBaseMapper.searchByVector(queryVectorStr, topK);

        } catch (Exception e) {
            log.error("檢索知識庫異常: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Long countKnowledge(String title,
                               String content,
                               String sourceType,
                               String sourcePath,
                               LocalDateTime startTime,
                               LocalDateTime endTime) {
        try {
            return knowledgeBaseMapper.countByFilters(title, content, sourceType, sourcePath, startTime, endTime);
        } catch (Exception e) {
            log.error("count knowledge failed: {}", e.getMessage(), e);
            return 0L;
        }
    }

    @Override
    public List<KnowledgeChunkRecord> pageKnowledge(String title,
                                                    String content,
                                                    String sourceType,
                                                    String sourcePath,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime,
                                                    long pageNo,
                                                    long pageSize) {
        try {
            long safePageNo = Math.max(1L, pageNo);
            long safePageSize = Math.max(1L, pageSize);
            long offset = (safePageNo - 1L) * safePageSize;
            return knowledgeBaseMapper.selectByFilters(
                    title,
                    content,
                    sourceType,
                    sourcePath,
                    startTime,
                    endTime,
                    safePageSize,
                    offset
            );
        } catch (Exception e) {
            log.error("page knowledge failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
