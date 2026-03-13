package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 长期记忆实体
 * 用于存储结构化的事实或关键信息，区别于向量知识库
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_memory")
public class AgentMemory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 记忆类别 (例如: "FACT", "RELATIONSHIP", "EVENT")
     */
    @TableField("category")
    private String category;

    /**
     * 记忆内容
     */
    @TableField("content")
    private String content;

    /**
     * 重要程度 (1-5)
     */
    @TableField("importance")
    private Integer importance;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
