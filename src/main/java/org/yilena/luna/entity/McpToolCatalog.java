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
 * McpToolCatalog ??
 */
public class McpToolCatalog implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    @JsonSerialize(using = ToStringSerializer.class) // 声明注解
    @TableId(value = "id", type = IdType.ASSIGN_ID) // 声明注解
    private Long id; // 声明成员字段

    @TableField("server_code") // 声明注解
    private String serverCode; // 声明成员字段

    @TableField("tool_name") // 声明注解
    private String toolName; // 声明成员字段

    @TableField("title") // 声明注解
    private String title; // 声明成员字段

    @TableField("description") // 声明注解
    private String description; // 声明成员字段

    @TableField(value = "input_schema", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> inputSchema; // 声明成员字段

    @TableField(value = "output_schema", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> outputSchema; // 声明成员字段

    @TableField(value = "annotations", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> annotations; // 声明成员字段

    @TableField(value = "tags", typeHandler = JsonbTypeHandler.class) // 声明注解
    private List<String> tags; // 声明成员字段

    @TableField("enabled") // 声明注解
    private Boolean enabled; // 声明成员字段

    @TableField("version") // 声明注解
    private String version; // 声明成员字段

    @TableField("requires_approval") // 声明注解
    private Boolean requiresApproval; // 声明成员字段

    @TableField("sensitivity") // 声明注解
    private String sensitivity; // 声明成员字段

    @TableField(value = "raw_payload", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> rawPayload; // 声明成员字段

    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class) // 声明注解
    private String embedding; // 声明成员字段

    @TableField("synced_at") // 声明注解
    private LocalDateTime syncedAt; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE) // 声明注解
    private LocalDateTime updatedAt; // 声明成员字段
} // 结束当前代码块
