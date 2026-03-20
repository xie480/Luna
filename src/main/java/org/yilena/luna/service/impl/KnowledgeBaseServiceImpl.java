package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.mapper.KnowledgeBaseMapper;
import org.yilena.luna.mq.dto.KnowledgeBaseMessage;
import org.yilena.luna.service.KnowledgeBaseService;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.List;

/**
 * 知識庫服務實現類
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> implements KnowledgeBaseService {

    private final LlmClientUtil llmClientUtil;
    private final RocketMQTemplate rocketMQTemplate;

    @Override
    public void addKnowledge(String title, String content, SourceType sourceType, String sourcePath) {
        // 將耗時的 Embedding 和寫入操作轉為異步 MQ 處理
        KnowledgeBaseMessage msg = KnowledgeBaseMessage.builder()
                .title(title)
                .content(content)
                .sourceType(sourceType.name())
                .sourcePath(sourcePath)
                .build();

        rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_KB_ADD, msg);
        log.info("已發送知識庫寫入請求至 MQ, 標題: {}", title);
    }

    @Override
    public List<KnowledgeBase> searchKnowledge(String query, int topK) {
        try {
            // 檢索操作需要實時返回，無法異步，仍保持同步調用
            String queryVectorStr = llmClientUtil.getEmbedding(query);

            if (queryVectorStr == null || queryVectorStr.trim().isEmpty() || queryVectorStr.trim().equals("[]")) {
                log.warn("查詢問題向量化失敗，無法進行檢索: {}", query);
                return Collections.emptyList();
            }

            log.debug("開始向量檢索，TopK: {}", topK);
            return this.baseMapper.searchByVector(queryVectorStr, topK);

        } catch (Exception e) {
            log.error("檢索知識庫異常: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
