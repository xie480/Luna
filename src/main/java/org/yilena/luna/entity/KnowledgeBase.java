package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.SourceType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地知識庫表
 * 存儲文件解析內容或聯網搜索結果，用於 RAG 檢索
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_base")
public class KnowledgeBase implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 標題/文件名/網頁標題
     */
    @TableField("title")
    private String title;

    /**
     * 原始文本內容 (分片後的內容)
     */
    @TableField("content")
    private String content;

    /**
     * 來源類型: FILE, WEB_SEARCH, MANUAL_INPUT
     */
    @TableField("source_type")
    private SourceType sourceType;

    /**
     * 來源標識 (如文件路徑、URL)
     */
    @TableField("source_path")
    private String sourcePath;

    /**
     * 向量數據庫中的 ID (用於關聯 Vector DB)
     */
    @TableField("vector_id")
    private String vectorId;

    /**
     * 文本的向量表示 (PGVector)
     * 格式如: "[0.1, 0.2, ...]"
     */
    @TableField("embedding")
    private String embedding;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
