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
/**
 * 工作流模板实体，负责保存复合能力模板的入参、出参与蓝图定义。
 */
public class WorkflowTemplate implements Serializable {

    /**
     * 序列化版本号，用于工作流模板持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    /**
     * 工作流模板主键。
     */
    private Long id;

    @TableField("workflow_name")
    /**
     * 工作流模板名称。
     */
    private String workflowName;

    @TableField("description")
    /**
     * 工作流模板描述。
     */
    private String description;

    @TableField(value = "input_schema", typeHandler = JsonbTypeHandler.class)
    /**
     * 工作流输入参数 JSON Schema。
     */
    private Map<String, Object> inputSchema;

    @TableField(value = "output_schema", typeHandler = JsonbTypeHandler.class)
    /**
     * 工作流输出结果 JSON Schema。
     */
    private Map<String, Object> outputSchema;

    @TableField(value = "required_capabilities", typeHandler = JsonbTypeHandler.class)
    /**
     * 工作流执行所依赖的能力列表。
     */
    private List<String> requiredCapabilities;

    @TableField(value = "tool_slots", typeHandler = JsonbTypeHandler.class)
    /**
     * 工作流工具插槽定义列表。
     */
    private List<Map<String, Object>> toolSlots;

    @TableField(value = "thought_chain", typeHandler = JsonbTypeHandler.class)
    /**
     * 工作流思维链或执行提示列表。
     */
    private List<String> thoughtChain;

    @TableField(value = "blueprint_json", typeHandler = JsonbTypeHandler.class)
    /**
     * 工作流蓝图 JSON，描述节点编排结构。
     */
    private Map<String, Object> blueprintJson;

    @TableField("enabled")
    /**
     * 是否启用当前工作流模板。
     */
    private Boolean enabled;

    @TableField("version")
    /**
     * 工作流模板版本号。
     */
    private String version;

    @TableField(value = "embedding", typeHandler = VectorTypeHandler.class)
    /**
     * 工作流模板语义向量，用于语义检索能力目录。
     */
    private String embedding;

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
