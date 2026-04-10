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
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 提示词策略版本实体，用于记录策略纳入与排除的 Prompt 集合以及版本启用状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "prompt_policy_version", autoResultMap = true)
public class PromptPolicyVersionEntity implements Serializable {

    /**
     * 策略版本主键 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属策略 ID。
     */
    @TableField("prompt_policy_id")
    private Long promptPolicyId;

    /**
     * 策略版本号。
     */
    @TableField("version_no")
    private String versionNo;

    /**
     * 纳入该策略的 Prompt 键列表。
     */
    @TableField(value = "include_prompt_keys", typeHandler = JsonbTypeHandler.class)
    private List<String> includePromptKeys;

    /**
     * 从该策略中排除的 Prompt 键列表。
     */
    @TableField(value = "exclude_prompt_keys", typeHandler = JsonbTypeHandler.class)
    private List<String> excludePromptKeys;

    /**
     * 版本状态。
     */
    @TableField("status")
    private String status;

    /**
     * 版本变更说明。
     */
    @TableField("change_note")
    private String changeNote;

    /**
     * 是否为当前激活版本。
     */
    @TableField("is_active")
    private Boolean isActive;

    /**
     * 版本创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 版本最后更新时间。
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
