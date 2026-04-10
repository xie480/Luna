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
@TableName(value = "mcp_resource_catalog", autoResultMap = true)
/**
 * MCP 资源目录实体，负责保存可读取资源的元数据和语义检索向量。
 */
public class McpResourceCatalog implements Serializable {

    /**
     * 序列化版本号，用于目录对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    /**
     * 资源目录记录主键。
     */
    private Long id;

    @TableField("server_code")
    /**
     * 资源所属 MCP 服务编码。
     */
    private String serverCode;

    @TableField("resource_uri")
    /**
     * 资源唯一 URI。
     */
    private String resourceUri;

    @TableField("name")
    /**
     * 资源名称。
     */
    private String name;

    @TableField("description")
    /**
     * 资源用途描述。
     */
    private String description;

    @TableField("mime_type")
    /**
     * 资源 MIME 类型。
     */
    private String mimeType;

    @TableField(value = "annotations", typeHandler = JsonbTypeHandler.class)
    /**
     * 资源扩展注解信息。
     */
    private Map<String, Object> annotations;

    @TableField(value = "raw_payload", typeHandler = JsonbTypeHandler.class)
    /**
     * 从远端同步回来的原始资源目录载荷。
     */
    private Map<String, Object> rawPayload;

    @TableField(value = "tags", typeHandler = JsonbTypeHandler.class)
    /**
     * 资源标签列表。
     */
    private List<String> tags;

    @TableField("enabled")
    /**
     * 是否启用当前资源。
     */
    private Boolean enabled;

    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    /**
     * 资源语义向量，用于语义检索目录。
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
