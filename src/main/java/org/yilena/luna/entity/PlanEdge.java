package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("plan_edge")
/**
 * PlanEdge ??
 */
public class PlanEdge implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    @TableId(value = "id", type = IdType.ASSIGN_ID) // 声明注解
    private Long id; // 声明成员字段

    @TableField("plan_id") // 声明注解
    private String planId; // 声明成员字段

    @TableField("from_node_id") // 声明注解
    private String fromNodeId; // 声明成员字段

    @TableField("to_node_id") // 声明注解
    private String toNodeId; // 声明成员字段

    @TableField("condition_expr") // 声明注解
    private String conditionExpr; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段
} // 结束当前代码块
