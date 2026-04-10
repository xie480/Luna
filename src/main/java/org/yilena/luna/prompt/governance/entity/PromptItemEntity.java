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
 * 提示词条目实体，用于定义单个 Prompt 的基础属性、分类归属和运行时装配特征。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("prompt_item")
public class PromptItemEntity implements Serializable {

    /**
     * Prompt 条目主键 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 兼容旧结构的分类名称字段。
     */
    @TableField("category")
    private String category;

    /**
     * 所属分类唯一键。
     */
    @TableField("category_key")
    private String categoryKey;

    /**
     * 子分类名称，用于细分条目归属。
     */
    @TableField("sub_category")
    private String subCategory;

    /**
     * Prompt 唯一键，用于跨版本稳定定位。
     */
    @TableField("prompt_key")
    private String promptKey;

    /**
     * Prompt 展示名称。
     */
    @TableField("prompt_name")
    private String promptName;

    /**
     * 运行时插槽，用于指明该 Prompt 在装配流程中的注入位置。
     */
    @TableField("runtime_slot")
    private String runtimeSlot;

    /**
     * 是否包含模板变量。
     */
    @TableField("has_template_variables")
    private Boolean hasTemplateVariables;

    /**
     * 是否启用关键词匹配。
     */
    @TableField("keyword_match_enabled")
    private Boolean keywordMatchEnabled;

    /**
     * Prompt 装配模式，用于控制拼接策略。
     */
    @TableField("assembly_mode")
    private String assemblyMode;

    /**
     * 条目是否启用。
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * 条目优先级，值越大通常越优先参与装配。
     */
    @TableField("priority")
    private Integer priority;

    /**
     * 当前条目状态。
     */
    @TableField("status")
    private String status;

    /**
     * 当前生效版本 ID。
     */
    @TableField("current_version_id")
    private Long currentVersionId;

    /**
     * 是否为系统内置 Prompt。
     */
    @TableField("is_builtin")
    private Boolean isBuiltin;

    /**
     * 条目说明。
     */
    @TableField("description")
    private String description;

    /**
     * 条目创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 条目最后更新时间。
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
