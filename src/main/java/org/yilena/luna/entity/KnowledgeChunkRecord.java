package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.SourceType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * knowledge_document + knowledge_chunk 联合查询结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunkRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long docId;
    private Long chunkId;
    private Integer chunkOrder;
    private String title;
    private String content;
    private SourceType sourceType;
    private String sourcePath;
    private String embedding;
    private Double vectorScore;
    private Double ftsScore;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
