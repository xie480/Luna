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

    private static final long serialVersionUID = 1L; // 声明成员字段

    @JsonSerialize(using = ToStringSerializer.class) // 声明注解
    @TableId(value = "id", type = IdType.ASSIGN_ID) // 声明注解
    private Long id; // 声明成员字段

    @TableField("server_code") // 声明注解
    private String serverCode; // 声明成员字段

    @TableField("server_name") // 声明注解
    private String serverName; // 声明成员字段

    @TableField("description") // 声明注解
    private String description; // 声明成员字段

    @TableField("base_url") // 声明注解
    private String baseUrl; // 声明成员字段

    @TableField("transport_type") // 声明注解
    private String transportType; // 声明成员字段

    @TableField("auth_type") // 声明注解
    private String authType; // 声明成员字段

    @TableField(value = "auth_config", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> authConfig; // 声明成员字段

    @TableField("enabled") // 声明注解
    private Boolean enabled; // 声明成员字段

    @TableField("health_status") // 声明注解
    private String healthStatus; // 声明成员字段

    @TableField("last_sync_at") // 声明注解
    private LocalDateTime lastSyncAt; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE) // 声明注解
    private LocalDateTime updatedAt; // 声明成员字段
} // 结束当前代码块
