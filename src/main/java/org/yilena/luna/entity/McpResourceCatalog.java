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
public class McpResourceCatalog implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("server_code")
    private String serverCode;

    @TableField("resource_uri")
    private String resourceUri;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("mime_type")
    private String mimeType;

    @TableField(value = "annotations", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> annotations;

    @TableField(value = "raw_payload", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> rawPayload;

    @TableField(value = "tags", typeHandler = JsonbTypeHandler.class)
    private List<String> tags;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    private String embedding;

    @TableField("synced_at")
    private LocalDateTime syncedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
