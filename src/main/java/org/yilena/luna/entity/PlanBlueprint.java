package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_blueprint", autoResultMap = true)
public class PlanBlueprint implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("plan_id")
    private String planId;

    @TableField("plan_version")
    private Integer planVersion;

    @TableField(value = "blueprint_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> blueprintJson;

    @TableField("generated_by_model")
    private String generatedByModel;

    @TableField("generated_at")
    private LocalDateTime generatedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
