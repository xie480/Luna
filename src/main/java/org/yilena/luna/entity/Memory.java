package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
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
 * 长期记忆实体类
 * 对应数据库表：luna_memory
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "luna_memory", autoResultMap = true)
public class Memory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID (雪花算法)
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话 ID 或日期标识（例如：2023:10:27）
     * 用于关联记忆与特定的时间段或会话
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 记忆类型
     * 例如：FACT (事实), PREFERENCE (偏好), SUMMARY (摘要), REFLECTION (反思)
     */
    @TableField("memory_type")
    private MemoryType memoryType;

    /**
     * 记忆内容
     * 存储具体的文本信息
     */
    @TableField("content")
    private String content;

    /**
     * 权重
     * 用于标识记忆的重要性，默认为 1
     */
    @TableField("weight")
    private Integer weight;

    /**
     * 向量表示（PGVector 文本）
     */
    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    private String embedding;

    /**
     * 创建时间
     * 插入时自动填充
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     * 插入和更新时自动填充
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
