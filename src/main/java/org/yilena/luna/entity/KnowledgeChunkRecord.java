package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.SourceType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识文档与知识分片的联合查询结果实体，用于承接检索阶段返回的文档元信息和分片内容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunkRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 联合结果主键 ID。
     */
    private Long id;

    /**
     * 所属知识文档 ID。
     */
    private Long docId;

    /**
     * 知识分片 ID。
     */
    private Long chunkId;

    /**
     * 分片在原文档中的顺序编号。
     */
    private Integer chunkOrder;

    /**
     * 文档标题，用于展示分片所属主题。
     */
    private String title;

    /**
     * 当前知识分片的正文内容。
     */
    private String content;

    /**
     * 知识来源类型，用于区分导入渠道。
     */
    private SourceType sourceType;

    /**
     * 来源路径或来源标识。
     */
    private String sourcePath;

    /**
     * 当前分片的向量表示结果。
     */
    private String embedding;

    /**
     * 向量检索得分，用于衡量语义匹配程度。
     */
    private Double vectorScore;

    /**
     * 全文检索得分，用于衡量关键词匹配程度。
     */
    private Double ftsScore;

    /**
     * 记录创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间。
     */
    private LocalDateTime updatedAt;
}
