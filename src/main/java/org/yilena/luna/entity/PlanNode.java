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
public class PlanNode implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "node_id", type = IdType.INPUT)
    private String nodeId;

    @TableField("plan_id")
    private String planId;

    @TableField("phase_id")
    private String phaseId;

    @TableField("name")
    private String name;

    @TableField("node_type")
    private PlanNodeType nodeType;

    @TableField("capability_type")
    private String capabilityType;

    @TableField("capability_name")
    private String capabilityName;

    @TableField("server_code")
    private String serverCode;

    @TableField(value = "input_json", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> inputJson;

    @TableField(value = "resolved_input_json", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> resolvedInputJson;

    @TableField(value = "expected_output_schema", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> expectedOutputSchema;

    @TableField(value = "dependencies", typeHandler = JsonbTypeHandler.class)
    private List<String> dependencies;

    @TableField("parallel_group")
    private String parallelGroup;

    @TableField("status")
    private PlanNodeStatus status;

    @TableField("approval_required")
    private Boolean approvalRequired;

    @TableField("approval_status")
    private String approvalStatus;

    @TableField(value = "retry_policy", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> retryPolicy;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("max_retry")
    private Integer maxRetry;

    @TableField("model_hint")
    private PlanModelHint modelHint;

    @TableField(value = "resource_hint", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> resourceHint;

    @TableField(value = "output_json", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> outputJson;

    @TableField(value = "output_for_next", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> outputForNext;

    @TableField("fail_reason")
    private String failReason;

    @TableField("last_error_stack_brief")
    private String lastErrorStackBrief;

    @TableField("risk_level")
    private PlanRiskLevel riskLevel;

    @TableField("cost_ms")
    private Long costMs;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
