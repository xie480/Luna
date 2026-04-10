package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 计划节点连线实体，用于描述计划图中节点之间的流转关系和条件分支。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("plan_edge")
public class PlanEdge implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 连线主键 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属计划 ID。
     */
    @TableField("plan_id")
    private String planId;

    /**
     * 起始节点 ID。
     */
    @TableField("from_node_id")
    private String fromNodeId;

    /**
     * 目标节点 ID。
     */
    @TableField("to_node_id")
    private String toNodeId;

    /**
     * 节点流转条件表达式，为空时表示默认流转。
     */
    @TableField("condition_expr")
    private String conditionExpr;

    /**
     * 连线创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
