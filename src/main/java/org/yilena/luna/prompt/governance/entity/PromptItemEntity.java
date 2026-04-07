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
@TableName("prompt_item")
public class PromptItemEntity implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("category")
    private String category;

    @TableField("category_key")
    private String categoryKey;

    @TableField("sub_category")
    private String subCategory;

    @TableField("prompt_key")
    private String promptKey;

    @TableField("prompt_name")
    private String promptName;

    @TableField("runtime_slot")
    private String runtimeSlot;

    @TableField("has_template_variables")
    private Boolean hasTemplateVariables;

    @TableField("keyword_match_enabled")
    private Boolean keywordMatchEnabled;

    @TableField("assembly_mode")
    private String assemblyMode;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("priority")
    private Integer priority;

    @TableField("status")
    private String status;

    @TableField("current_version_id")
    private Long currentVersionId;

    @TableField("is_builtin")
    private Boolean isBuiltin;

    @TableField("description")
    private String description;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
