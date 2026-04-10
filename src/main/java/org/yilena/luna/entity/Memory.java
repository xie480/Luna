package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.MemoryType;
import org.yilena.luna.handler.VectorTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 长期记忆实体，用于持久化用户事实、偏好和摘要等内容，为后续对话提供记忆召回基础。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "luna_memory", autoResultMap = true)
public class Memory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记忆主键 ID。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属会话 ID 或时间段标识，用于关联记忆来源上下文。
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 记忆类型，用于区分事实、偏好、摘要等不同语义。
     */
    @TableField("memory_type")
    private MemoryType memoryType;

    /**
     * 记忆正文内容。
     */
    @TableField("content")
    private String content;

    /**
     * 记忆权重，用于衡量该条内容的重要程度。
     */
    @TableField("weight")
    private Integer weight;

    /**
     * 记忆内容的向量表示，用于语义检索召回。
     */
    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    private String embedding;

    /**
     * 记忆创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 记忆最后更新时间。
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
