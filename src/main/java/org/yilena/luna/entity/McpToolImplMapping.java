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
@TableName(value = "mcp_tool_impl_mapping", autoResultMap = true)
/**
 * MCP 工具实现映射实体，负责描述目录工具与本地实现方式之间的绑定关系。
 */
public class McpToolImplMapping implements Serializable {

    /**
     * 序列化版本号，用于映射对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    /**
     * 工具实现映射记录主键。
     */
    private Long id;

    @TableField("server_code")
    /**
     * 工具所属 MCP 服务编码。
     */
    private String serverCode;

    @TableField("tool_name")
    /**
     * 工具名称。
     */
    private String toolName;

    @TableField("impl_type")
    /**
     * 实现类型，例如本地处理器、HTTP、RPC 或工作流。
     */
    private String implType;

    @TableField("execution_mode")
    /**
     * 执行模式，例如 MCP 或兼容旧模式。
     */
    private String executionMode;

    @TableField("bean_name")
    /**
     * Spring Bean 名称，适用于 Spring Bean 调用方式。
     */
    private String beanName;

    @TableField("method_name")
    /**
     * Bean 方法名称，适用于 Spring Bean 调用方式。
     */
    private String methodName;

    @TableField("route_uri")
    /**
     * 远端路由地址，适用于 HTTP 或 RPC 调用方式。
     */
    private String routeUri;

    @TableField("timeout_ms")
    /**
     * 调用超时时间，单位为毫秒。
     */
    private Integer timeoutMs;

    @TableField(value = "retry_policy", typeHandler = JsonbTypeHandler.class)
    /**
     * 调用失败后的重试策略配置 JSON。
     */
    private Map<String, Object> retryPolicy;

    @TableField("enabled")
    /**
     * 是否启用当前实现映射。
     */
    private Boolean enabled;

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
