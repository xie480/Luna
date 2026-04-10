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
import org.yilena.luna.handler.JsonbTypeHandler;
import org.yilena.luna.handler.VectorTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "mcp_tool_catalog", autoResultMap = true)
/**
 * MCP 工具目录实体，负责保存工具元数据、入参出参结构和语义检索向量。
 */
public class McpToolCatalog implements Serializable {

    /**
     * 序列化版本号，用于目录对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    /**
     * 工具目录记录主键。
     */
    private Long id;

    @TableField("server_code")
    /**
     * 工具所属 MCP 服务编码。
     */
    private String serverCode;

    @TableField("tool_name")
    /**
     * 工具唯一名称。
     */
    private String toolName;

    @TableField("title")
    /**
     * 工具标题，用于前端或目录展示。
     */
    private String title;

    @TableField("description")
    /**
     * 工具业务描述。
     */
    private String description;

    @TableField(value = "input_schema", typeHandler = JsonbTypeHandler.class)
    /**
     * 工具输入参数 JSON Schema。
     */
    private Map<String, Object> inputSchema;

    @TableField(value = "output_schema", typeHandler = JsonbTypeHandler.class)
    /**
     * 工具输出结果 JSON Schema。
     */
    private Map<String, Object> outputSchema;

    @TableField(value = "annotations", typeHandler = JsonbTypeHandler.class)
    /**
     * 工具扩展注解信息。
     */
    private Map<String, Object> annotations;

    @TableField(value = "tags", typeHandler = JsonbTypeHandler.class)
    /**
     * 工具标签列表，用于分类和检索。
     */
    private List<String> tags;

    @TableField("enabled")
    /**
     * 是否启用当前工具。
     */
    private Boolean enabled;

    @TableField("version")
    /**
     * 工具版本号。
     */
    private String version;

    @TableField("execution_mode")
    /**
     * 工具执行模式，例如 MCP 或兼容旧模式。
     */
    private String executionMode;

    @TableField("requires_approval")
    /**
     * 是否需要人工审批后才能调用。
     */
    private Boolean requiresApproval;

    @TableField("sensitivity")
    /**
     * 工具敏感等级。
     */
    private String sensitivity;

    @TableField(value = "raw_payload", typeHandler = JsonbTypeHandler.class)
    /**
     * 从远端服务同步回来的原始目录载荷。
     */
    private Map<String, Object> rawPayload;

    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    /**
     * 工具语义向量，用于语义检索能力目录。
     */
    private String embedding;

    @TableField("synced_at")
    /**
     * 最近一次同步时间。
     */
    private LocalDateTime syncedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    /**
     * 记录创建时间。
     */
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    /**
     * 记录最后更新时间。
     */
    private LocalDateTime updatedAt;
}
