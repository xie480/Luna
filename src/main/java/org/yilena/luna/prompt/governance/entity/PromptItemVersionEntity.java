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
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "prompt_item_version", autoResultMap = true)
public class PromptItemVersionEntity implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("prompt_item_id")
    private Long promptItemId;

    @TableField("version_no")
    private String versionNo;

    @TableField("version_label")
    private String versionLabel;

    @TableField("prompt_value")
    private String promptValue;

    @TableField(value = "template_variables", typeHandler = JsonbTypeHandler.class)
    private List<String> templateVariables;

    @TableField(value = "match_keywords", typeHandler = JsonbTypeHandler.class)
    private List<String> matchKeywords;

    @TableField(value = "match_scope", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> matchScope;

    @TableField(value = "edit_policy", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> editPolicy;

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
