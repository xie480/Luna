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
@TableName(value = "workflow_template", autoResultMap = true)
public class WorkflowTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("workflow_name")
    private String workflowName;

    @TableField("description")
    private String description;

    @TableField(value = "input_schema", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> inputSchema;

    @TableField(value = "output_schema", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> outputSchema;

    @TableField(value = "required_capabilities", typeHandler = JsonbTypeHandler.class)
    private List<String> requiredCapabilities;

    @TableField(value = "tool_slots", typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> toolSlots;

    @TableField(value = "thought_chain", typeHandler = JsonbTypeHandler.class)
    private List<String> thoughtChain;

    @TableField(value = "blueprint_json", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> blueprintJson;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("version")
    private String version;

    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    private String embedding;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
