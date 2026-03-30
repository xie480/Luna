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
 * McpToolImplMapping ??
 */
public class McpToolImplMapping implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    @JsonSerialize(using = ToStringSerializer.class) // 声明注解
    @TableId(value = "id", type = IdType.ASSIGN_ID) // 声明注解
    private Long id; // 声明成员字段

    @TableField("server_code") // 声明注解
    private String serverCode; // 声明成员字段

    @TableField("tool_name") // 声明注解
    private String toolName; // 声明成员字段

    @TableField("impl_type") // 声明注解
    private String implType; // 声明成员字段

    @TableField("bean_name") // 声明注解
    private String beanName; // 声明成员字段

    @TableField("method_name") // 声明注解
    private String methodName; // 声明成员字段

    @TableField("route_uri") // 声明注解
    private String routeUri; // 声明成员字段

    @TableField("timeout_ms") // 声明注解
    private Integer timeoutMs; // 声明成员字段

    @TableField(value = "retry_policy", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> retryPolicy; // 声明成员字段

    @TableField("enabled") // 声明注解
    private Boolean enabled; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE) // 声明注解
    private LocalDateTime updatedAt; // 声明成员字段
} // 结束当前代码块
