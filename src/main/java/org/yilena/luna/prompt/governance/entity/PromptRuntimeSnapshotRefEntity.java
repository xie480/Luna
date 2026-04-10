package org.yilena.luna.prompt.governance.entity;

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
 * 运行时提示词快照引用实体，用于将会话执行现场与实际命中的 Prompt 版本建立追踪关系。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("prompt_runtime_snapshot_ref")
public class PromptRuntimeSnapshotRefEntity implements Serializable {

    /**
     * 快照引用主键 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属会话 ID。
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 所属对话轮次 ID。
     */
    @TableField("round_id")
    private Long roundId;

    /**
     * 所属执行节点 ID。
     */
    @TableField("node_id")
    private Long nodeId;

    /**
     * 运行时快照 ID。
     */
    @TableField("snapshot_id")
    private String snapshotId;

    /**
     * 命中的 Prompt 条目 ID。
     */
    @TableField("prompt_item_id")
    private Long promptItemId;

    /**
     * 命中的 Prompt 版本 ID。
     */
    @TableField("prompt_item_version_id")
    private Long promptItemVersionId;

    /**
     * 命中的 Prompt 键。
     */
    @TableField("prompt_key")
    private String promptKey;

    /**
     * 命中的 Prompt 版本号。
     */
    @TableField("prompt_version_no")
    private String promptVersionNo;

    /**
     * 命中的策略 ID。
     */
    @TableField("policy_id")
    private Long policyId;

    /**
     * 命中的策略键。
     */
    @TableField("policy_key")
    private String policyKey;

    /**
     * 提示词装配器版本号。
     */
    @TableField("assembler_version")
    private String assemblerVersion;

    /**
     * Prompt 注入的运行时插槽。
     */
    @TableField("runtime_slot")
    private String runtimeSlot;

    /**
     * 命中原因说明。
     */
    @TableField("match_reason")
    private String matchReason;

    /**
     * 是否应用了策略过滤。
     */
    @TableField("policy_applied")
    private Boolean policyApplied;

    /**
     * 最终解析后的 Prompt 内容。
     */
    @TableField("resolved_value")
    private String resolvedValue;

    /**
     * 快照引用创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
