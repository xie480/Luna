package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MCP 工具實體 (對應 mcp_tools 表)
 * 原子能力，無狀態，同步執行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("mcp_tools")
public class McpTool implements Serializable {

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

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
