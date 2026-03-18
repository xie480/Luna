package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.enums.Sensitivity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MCP 技能實體 (對應 mcp_skills 表)
 * 複合能力，支持異步、審批、權限控制
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("mcp_skills")
public class McpSkill implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("version")
    private String version;

    @TableField("owner")
    private String owner;

    @TableField("bean_name")
    private String beanName;

    @TableField("method_name")
    private String methodName;

    @TableField("input_schema")
    private String inputSchema;

    @TableField("output_schema")
    private String outputSchema;

    @TableField("run_mode")
    private RunMode runMode;

    @TableField("requires_approval")
    private Boolean requiresApproval;

    @TableField("sensitivity")
    private Sensitivity sensitivity;

    /**
     * 文本的向量表示 (PGVector)
     */
    @TableField("embedding")
    private String embedding;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
