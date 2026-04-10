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
 * MCP 服务注册实体，负责保存已接入 MCP 服务的连接信息和同步状态。
 */
public class McpServerRegistry implements Serializable {

    /**
     * 序列化版本号，用于注册表对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    /**
     * 服务注册记录主键。
     */
    private Long id;

    @TableField("server_code")
    /**
     * MCP 服务唯一编码，用于路由到具体服务。
     */
    private String serverCode;

    @TableField("server_name")
    /**
     * MCP 服务展示名称。
     */
    private String serverName;

    @TableField("description")
    /**
     * MCP 服务职责描述。
     */
    private String description;

    @TableField("base_url")
    /**
     * MCP 服务基础访问地址。
     */
    private String baseUrl;

    @TableField("transport_type")
    /**
     * 传输协议类型，例如 HTTP、SSE 或其他协议实现。
     */
    private String transportType;

    @TableField("auth_type")
    /**
     * 服务接入认证方式。
     */
    private String authType;

    @TableField(value = "auth_config", typeHandler = JsonbTypeHandler.class)
    /**
     * 认证配置 JSON，例如 token、请求头或凭证信息。
     */
    private Map<String, Object> authConfig;

    @TableField("enabled")
    /**
     * 是否启用当前 MCP 服务。
     */
    private Boolean enabled;

    @TableField("health_status")
    /**
     * 服务健康状态，例如正常、异常或未知。
     */
    private String healthStatus;

    @TableField("last_sync_at")
    /**
     * 最近一次同步目录的时间。
     */
    private LocalDateTime lastSyncAt;

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
