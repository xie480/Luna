package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
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
@TableName(value = "plan_blueprint", autoResultMap = true)
/**
 * PlanBlueprint ??
 */
public class PlanBlueprint implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    @TableId(value = "id", type = IdType.ASSIGN_ID) // 声明注解
    private Long id; // 声明成员字段

    @TableField("plan_id") // 声明注解
    private String planId; // 声明成员字段

    @TableField("plan_version") // 声明注解
    private Integer planVersion; // 声明成员字段

    @TableField(value = "blueprint_json", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> blueprintJson; // 声明成员字段

    @TableField("generated_by_model") // 声明注解
    private String generatedByModel; // 声明成员字段

    @TableField("generated_at") // 声明注解
    private LocalDateTime generatedAt; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段
} // 结束当前代码块
