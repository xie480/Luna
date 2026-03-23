package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.handler.VectorTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地知识库表
 * 存储文件解析内容或联网搜索结果，用于 RAG 检索
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "knowledge_base", autoResultMap = true)
public class KnowledgeBase implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID (雪花算法)
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 标题/文件名/网页标题
     */
    @TableField("title")
    private String title;

    /**
     * 原始文本内容 (分片后的内容)
     */
    @TableField("content")
    private String content;

    /**
     * 来源类型: FILE, WEB_SEARCH, MANUAL_INPUT
     */
    @TableField("source_type")
    private SourceType sourceType;

    /**
     * 来源标识 (如文件路径、URL)
     */
    @TableField("source_path")
    private String sourcePath;

    /**
     * 向量数据库中的 ID (用于关联外部 Vector DB，若使用 PGVector 则可选)
     */
    @TableField("vector_id")
    private String vectorId;

    /**
     * 文本的向量表示 (PGVector)
     * 格式如: "[0.1, 0.2, ...]"
     */
    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    private String embedding;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
