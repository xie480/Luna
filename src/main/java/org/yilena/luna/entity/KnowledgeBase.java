package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.SourceType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 已废弃的知识库兼容实体，用于承接旧版 knowledge_base 数据结构并兼容历史调用链路。
 * <p>
 * 当前知识数据已迁移到 knowledge_document 与 knowledge_chunk 表，该类主要用于过渡期的数据读取与返回封装。
 */
@Deprecated
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 知识记录主键 ID。
     */
    private Long id;

    /**
     * 知识标题，用于概括当前知识内容主题。
     */
    private String title;

    /**
     * 知识正文内容。
     */
    private String content;

    /**
     * 知识来源类型，用于区分手工录入、文件导入等来源渠道。
     */
    private SourceType sourceType;

    /**
     * 来源路径或来源标识，用于追踪原始数据位置。
     */
    private String sourcePath;

    /**
     * 向量库中的外部向量标识。
     */
    private String vectorId;

    /**
     * 文本内容对应的向量表示结果。
     */
    private String embedding;

    /**
     * 记录创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间。
     */
    private LocalDateTime updatedAt;
}
