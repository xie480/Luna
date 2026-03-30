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

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "mcp_server_registry", autoResultMap = true)
/**
 * McpServerRegistry ??
 */
public class McpServerRegistry implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("server_code")
    private String serverCode;

    @TableField("server_name")
    private String serverName;

    @TableField("description")
    private String description;

    @TableField("base_url")
    private String baseUrl;

    @TableField("transport_type")
    private String transportType;

    @TableField("auth_type")
    private String authType;

    @TableField(value = "auth_config", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> authConfig;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("health_status")
    private String healthStatus;

    @TableField("last_sync_at")
    private LocalDateTime lastSyncAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
