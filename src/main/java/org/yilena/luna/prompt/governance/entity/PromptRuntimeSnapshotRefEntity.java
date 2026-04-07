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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("prompt_runtime_snapshot_ref")
public class PromptRuntimeSnapshotRefEntity implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("round_id")
    private Long roundId;

    @TableField("snapshot_id")
    private String snapshotId;

    @TableField("prompt_item_id")
    private Long promptItemId;

    @TableField("prompt_item_version_id")
    private Long promptItemVersionId;

    @TableField("prompt_key")
    private String promptKey;

    @TableField("prompt_version_no")
    private String promptVersionNo;

    @TableField("policy_id")
    private String policyId;

    @TableField("assembler_version")
    private String assemblerVersion;

    @TableField("runtime_slot")
    private String runtimeSlot;

    @TableField("match_reason")
    private String matchReason;

    @TableField("resolved_value")
    private String resolvedValue;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
