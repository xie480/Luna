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
 * 知识库服务实现类，负责知识入库消息投递、向量检索以及后台分页查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    /**
     * LLM 客户端工具，用于生成检索查询向量。
     */
    private final LlmClientUtil llmClientUtil;
    /**
     * RocketMQ 模板，用于异步投递知识入库请求。
     */
    private final RocketMQTemplate rocketMQTemplate;
    /**
     * 知识库 Mapper，负责访问知识分片表。
     */
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    /**
     * 提交一条知识入库请求到消息队列，由异步消费者完成分片和持久化。
     */
    public void addKnowledge(String title, String content, SourceType sourceType, String sourcePath) {
        /**
         * 统一把来源类型转换为对外稳定的 value，
         * 避免历史枚举序列化差异影响消费者解析。
         */
        KnowledgeBaseMessage msg = KnowledgeBaseMessage.builder()
                .title(title)
                .content(content)
                .sourceType(sourceType != null ? sourceType.getValue() : null)
                .sourcePath(sourcePath)
                .build();

        /**
         * 入库流程采用异步投递，减少接口写入延迟并把重处理能力交给 MQ 消费链路。
         */
        rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_KB_ADD, msg);
        log.info("宸插彂閫佺煡璇嗗簱瀵煎叆璇锋眰鑷?MQ, 鏍囬: {}, sourceType={}", title, msg.getSourceType());
    }

    @Override
    /**
     * 根据查询文本执行向量检索，返回最相关的知识片段。
     */
    public List<KnowledgeChunkRecord> searchKnowledge(String query, int topK) {
        try {
            /**
             * 检索前先生成查询向量，若向量为空则直接返回空结果，
             * 避免数据库执行无效相似度查询。
             */
            String queryVectorStr = llmClientUtil.getEmbedding(query);

            if (queryVectorStr == null || queryVectorStr.trim().isEmpty() || queryVectorStr.trim().equals("[]")) {
                log.warn("鏌ヨ鏂囨湰鍚戦噺鍖栧け璐ワ紝鏃犳硶杩涜妫€绱? {}", query);
                return Collections.emptyList();
            }

            /**
             * 使用生成好的向量执行 TopK 检索，
             * 返回最相关的知识分片。
             */
            log.debug("寮€濮嬪悜閲忔绱紝TopK: {}", topK);
            return knowledgeBaseMapper.searchByVector(queryVectorStr, topK);

        } catch (Exception e) {
            log.error("妫€绱㈢煡璇嗗簱寮傚父: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    /**
     * 统计满足筛选条件的知识片段总数，供分页接口计算总页数。
     */
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
    /**
     * 按条件分页查询知识片段，供后台管理页面展示。
     */
    public List<KnowledgeChunkRecord> pageKnowledge(String title,
                                                    String content,
                                                    String sourceType,
                                                    String sourcePath,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime,
                                                    long pageNo,
                                                    long pageSize) {
        try {
            /**
             * 先规范分页参数并计算偏移量，
             * 避免负数页码或页大小影响数据库查询。
             */
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
