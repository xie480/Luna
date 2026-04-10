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
 * 提示词策略实体，用于管理 Prompt 组合策略的基础信息和当前生效版本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("prompt_policy")
public class PromptPolicyEntity implements Serializable {

    /**
     * 策略主键 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 策略唯一键，用于程序内部引用。
     */
    @TableField("policy_key")
    private String policyKey;

    /**
     * 策略名称，用于界面展示。
     */
    @TableField("policy_name")
    private String policyName;

    /**
     * 策略说明，用于描述适用场景和装配目的。
     */
    @TableField("description")
    private String description;

    /**
     * 策略是否启用。
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * 当前生效的策略版本 ID。
     */
    @TableField("current_version_id")
    private Long currentVersionId;

    /**
     * 策略创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 策略最后更新时间。
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
