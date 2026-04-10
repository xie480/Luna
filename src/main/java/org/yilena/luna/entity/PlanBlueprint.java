package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
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
 * 计划蓝图实体，负责保存计划编排生成的原始结构化蓝图快照。
 */
public class PlanBlueprint implements Serializable {

    /**
     * 序列化版本号，用于蓝图对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    /**
     * 蓝图记录主键。
     */
    private Long id;

    @TableField("plan_id")
    /**
     * 蓝图所属的计划 ID。
     */
    private String planId;

    @TableField("plan_version")
    /**
     * 蓝图对应的计划版本号，用于区分多次重规划结果。
     */
    private Integer planVersion;

    @TableField(value = "blueprint_json", typeHandler = JsonbTypeHandler.class)
    /**
     * 计划蓝图的完整 JSON 内容，记录节点、阶段和依赖结构。
     */
    private Map<String, Object> blueprintJson;

    @TableField("generated_by_model")
    /**
     * 生成该蓝图所使用的模型名称。
     */
    private String generatedByModel;

    @TableField("generated_at")
    /**
     * 蓝图生成时间。
     */
    private LocalDateTime generatedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    /**
     * 记录创建时间。
     */
    private LocalDateTime createdAt;
}
