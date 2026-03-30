package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.*;
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_node", autoResultMap = true)
/**
 * PlanNode ??
 */
public class PlanNode implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    @TableId(value = "node_id", type = IdType.INPUT) // 声明注解
    private String nodeId; // 声明成员字段

    @TableField("plan_id") // 声明注解
    private String planId; // 声明成员字段

    @TableField("phase_id") // 声明注解
    private String phaseId; // 声明成员字段

    @TableField("name") // 声明注解
    private String name; // 声明成员字段

    @TableField("node_type") // 声明注解
    private PlanNodeType nodeType; // 声明成员字段

    @TableField("capability_type") // 声明注解
    private String capabilityType; // 声明成员字段

    @TableField("capability_name") // 声明注解
    private String capabilityName; // 声明成员字段

    @TableField("server_code") // 声明注解
    private String serverCode; // 声明成员字段

    @TableField(value = "input_json", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> inputJson; // 声明成员字段

    @TableField(value = "resolved_input_json", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> resolvedInputJson; // 声明成员字段

    @TableField(value = "expected_output_schema", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> expectedOutputSchema; // 声明成员字段

    @TableField(value = "dependencies", typeHandler = JsonbTypeHandler.class) // 声明注解
    private List<String> dependencies; // 声明成员字段

    @TableField("parallel_group") // 声明注解
    private String parallelGroup; // 声明成员字段

    @TableField("status") // 声明注解
    private PlanNodeStatus status; // 声明成员字段

    @TableField("approval_required") // 声明注解
    private Boolean approvalRequired; // 声明成员字段

    @TableField("approval_status") // 声明注解
    private String approvalStatus; // 声明成员字段

    @TableField(value = "retry_policy", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> retryPolicy; // 声明成员字段

    @TableField("retry_count") // 声明注解
    private Integer retryCount; // 声明成员字段

    @TableField("max_retry") // 声明注解
    private Integer maxRetry; // 声明成员字段

    @TableField("model_hint") // 声明注解
    private PlanModelHint modelHint; // 声明成员字段

    @TableField(value = "resource_hint", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> resourceHint; // 声明成员字段

    @TableField(value = "output_json", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> outputJson; // 声明成员字段

    @TableField(value = "output_for_next", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> outputForNext; // 声明成员字段

    @TableField("fail_reason") // 声明注解
    private String failReason; // 声明成员字段

    @TableField("last_error_stack_brief") // 声明注解
    private String lastErrorStackBrief; // 声明成员字段

    @TableField("risk_level") // 声明注解
    private PlanRiskLevel riskLevel; // 声明成员字段

    @TableField("cost_ms") // 声明注解
    private Long costMs; // 声明成员字段

    @TableField("started_at") // 声明注解
    private LocalDateTime startedAt; // 声明成员字段

    @TableField("finished_at") // 声明注解
    private LocalDateTime finishedAt; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE) // 声明注解
    private LocalDateTime updatedAt; // 声明成员字段
} // 结束当前代码块
