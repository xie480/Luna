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
 * 计划节点实体，负责描述计划图中的单个执行节点及其运行过程数据。
 */
public class PlanNode implements Serializable {

    /**
     * 序列化版本号，用于节点对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @TableId(value = "node_id", type = IdType.INPUT)
    /**
     * 节点唯一标识。
     */
    private String nodeId;

    @TableField("plan_id")
    /**
     * 节点所属计划 ID。
     */
    private String planId;

    @TableField("phase_id")
    /**
     * 节点所属阶段 ID。
     */
    private String phaseId;

    @TableField("name")
    /**
     * 节点名称，用于执行图和日志展示。
     */
    private String name;

    @TableField("node_type")
    /**
     * 节点类型，用于区分分析、工具、报告等执行形态。
     */
    private PlanNodeType nodeType;

    @TableField("capability_type")
    /**
     * 节点绑定的能力类型，例如工具、工作流或资源。
     */
    private String capabilityType;

    @TableField("capability_name")
    /**
     * 节点绑定的能力名称。
     */
    private String capabilityName;

    @TableField("server_code")
    /**
     * 节点调用的目标服务编码。
     */
    private String serverCode;

    @TableField(value = "input_json", typeHandler = JsonbTypeHandler.class)
    /**
     * 节点原始输入参数 JSON。
     */
    private Map<String, Object> inputJson;

    @TableField(value = "resolved_input_json", typeHandler = JsonbTypeHandler.class)
    /**
     * 变量解析后的实际输入参数 JSON。
     */
    private Map<String, Object> resolvedInputJson;

    @TableField(value = "expected_output_schema", typeHandler = JsonbTypeHandler.class)
    /**
     * 节点预期输出结构约束，用于校验执行结果。
     */
    private Map<String, Object> expectedOutputSchema;

    @TableField(value = "dependencies", typeHandler = JsonbTypeHandler.class)
    /**
     * 当前节点依赖的前置节点 ID 列表。
     */
    private List<String> dependencies;

    @TableField("parallel_group")
    /**
     * 并行分组标识，相同分组的节点可并行执行。
     */
    private String parallelGroup;

    @TableField("status")
    /**
     * 节点当前执行状态。
     */
    private PlanNodeStatus status;

    @TableField("approval_required")
    /**
     * 是否需要人工审批后才能继续执行。
     */
    private Boolean approvalRequired;

    @TableField("approval_status")
    /**
     * 审批流程状态，例如待审批、已通过或已拒绝。
     */
    private String approvalStatus;

    @TableField(value = "retry_policy", typeHandler = JsonbTypeHandler.class)
    /**
     * 节点重试策略配置 JSON。
     */
    private Map<String, Object> retryPolicy;

    @TableField("retry_count")
    /**
     * 当前已执行的重试次数。
     */
    private Integer retryCount;

    @TableField("max_retry")
    /**
     * 允许的最大重试次数。
     */
    private Integer maxRetry;

    @TableField("model_hint")
    /**
     * 节点建议使用的模型规格提示。
     */
    private PlanModelHint modelHint;

    @TableField(value = "resource_hint", typeHandler = JsonbTypeHandler.class)
    /**
     * 节点资源选择提示 JSON，用于约束执行资源范围。
     */
    private Map<String, Object> resourceHint;

    @TableField(value = "output_json", typeHandler = JsonbTypeHandler.class)
    /**
     * 节点原始输出结果 JSON。
     */
    private Map<String, Object> outputJson;

    @TableField(value = "output_for_next", typeHandler = JsonbTypeHandler.class)
    /**
     * 提供给下游节点继续消费的裁剪后输出。
     */
    private Map<String, Object> outputForNext;

    @TableField("fail_reason")
    /**
     * 节点失败原因摘要。
     */
    private String failReason;

    @TableField("last_error_stack_brief")
    /**
     * 最近一次异常堆栈的简要摘要，便于排障。
     */
    private String lastErrorStackBrief;

    @TableField("risk_level")
    /**
     * 节点风险等级，用于审批和执行策略判断。
     */
    private PlanRiskLevel riskLevel;

    @TableField("cost_ms")
    /**
     * 节点执行耗时，单位为毫秒。
     */
    private Long costMs;

    @TableField("started_at")
    /**
     * 节点开始执行时间。
     */
    private LocalDateTime startedAt;

    @TableField("finished_at")
    /**
     * 节点结束执行时间。
     */
    private LocalDateTime finishedAt;

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
