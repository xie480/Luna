package org.yilena.luna.service;

import org.yilena.luna.entity.KnowledgeChunkRecord;
import org.yilena.luna.enums.SourceType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库服务接口，负责管理知识内容入库、向量检索和分页查询能力。
 * 该接口为 RAG 链路提供知识写入与召回的统一服务入口。
 */
public interface KnowledgeBaseService {

    /**
     * 添加知识内容到知识库，并完成分片与向量化处理。
     * @param title 知识标题
     * @param content 原始知识内容
     * @param sourceType 知识来源类型
     * @param sourcePath 知识来源路径
     */
    void addKnowledge(String title, String content, SourceType sourceType, String sourcePath);

    /**
     * 按查询语义从知识库中检索最相关的内容片段。
     * @param query 查询语句
     * @param topK 返回结果数量
     * @return 匹配到的知识分片列表
     */
    List<KnowledgeChunkRecord> searchKnowledge(String query, int topK);

    /**
     * 按筛选条件统计知识库记录总数，供分页查询前获取总量。
     * @param title 标题过滤条件
     * @param content 内容过滤条件
     * @param sourceType 来源类型过滤条件
     * @param sourcePath 来源路径过滤条件
     * @param startTime 创建时间起始值
     * @param endTime 创建时间结束值
     * @return 满足条件的知识记录数量
     */
    Long countKnowledge(String title,
                        String content,
                        String sourceType,
                        String sourcePath,
                        LocalDateTime startTime,
                        LocalDateTime endTime);

    /**
     * 按筛选条件分页查询知识库记录，供后台管理界面浏览与检索。
     * @param title 标题过滤条件
     * @param content 内容过滤条件
     * @param sourceType 来源类型过滤条件
     * @param sourcePath 来源路径过滤条件
     * @param startTime 创建时间起始值
     * @param endTime 创建时间结束值
     * @param pageNo 页码
     * @param pageSize 每页数量
     * @return 当前页的知识记录列表
     */
    List<KnowledgeChunkRecord> pageKnowledge(String title,
                                             String content,
                                             String sourceType,
                                             String sourcePath,
                                             LocalDateTime startTime,
                                             LocalDateTime endTime,
                                             long pageNo,
                                             long pageSize);
}
