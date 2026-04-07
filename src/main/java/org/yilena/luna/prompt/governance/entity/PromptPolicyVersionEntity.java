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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "prompt_policy_version", autoResultMap = true)
public class PromptPolicyVersionEntity implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("prompt_policy_id")
    private Long promptPolicyId;

    @TableField("version_no")
    private String versionNo;

    @TableField(value = "include_prompt_keys", typeHandler = JsonbTypeHandler.class)
    private List<String> includePromptKeys;

    @TableField(value = "exclude_prompt_keys", typeHandler = JsonbTypeHandler.class)
    private List<String> excludePromptKeys;

    @TableField("status")
    private String status;

    @TableField("change_note")
    private String changeNote;

    @TableField("is_active")
    private Boolean isActive;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

