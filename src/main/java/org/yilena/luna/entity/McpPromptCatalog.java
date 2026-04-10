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
@TableName(value = "mcp_prompt_catalog", autoResultMap = true)
/**
 * MCP 提示词目录实体，负责保存提示词模板元数据和语义检索向量。
 */
public class McpPromptCatalog implements Serializable {

    /**
     * 序列化版本号，用于目录对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    /**
     * 提示词目录记录主键。
     */
    private Long id;

    @TableField("server_code")
    /**
     * 提示词所属 MCP 服务编码。
     */
    private String serverCode;

    @TableField("prompt_name")
    /**
     * 提示词模板名称。
     */
    private String promptName;

    @TableField("title")
    /**
     * 提示词标题。
     */
    private String title;

    @TableField("description")
    /**
     * 提示词用途描述。
     */
    private String description;

    @TableField(value = "arguments_schema", typeHandler = JsonbTypeHandler.class)
    /**
     * 提示词参数 JSON Schema。
     */
    private Map<String, Object> argumentsSchema;

    @TableField(value = "raw_payload", typeHandler = JsonbTypeHandler.class)
    /**
     * 从远端同步回来的原始提示词目录载荷。
     */
    private Map<String, Object> rawPayload;

    @TableField(value = "tags", typeHandler = JsonbTypeHandler.class)
    /**
     * 提示词标签列表，用于分类与检索。
     */
    private List<String> tags;

    @TableField("enabled")
    /**
     * 是否启用当前提示词。
     */
    private Boolean enabled;

    @TableField("version")
    /**
     * 提示词版本号。
     */
    private String version;

    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    /**
     * 提示词语义向量，用于语义检索目录。
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
