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
 * 提示词分类实体，用于维护 Prompt 治理体系中的分类目录，支撑分类归档、启停控制和排序展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("prompt_category")
public class PromptCategoryEntity implements Serializable {

    /**
     * 分类主键 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 分类唯一键，用于程序内部定位分类。
     */
    @TableField("category_key")
    private String categoryKey;

    /**
     * 分类名称，用于界面展示。
     */
    @TableField("category_name")
    private String categoryName;

    /**
     * 父级分类键，用于构建分类层级。
     */
    @TableField("parent_category_key")
    private String parentCategoryKey;

    /**
     * 分类说明，用于描述该分类承载的 Prompt 范围。
     */
    @TableField("description")
    private String description;

    /**
     * 排序值，值越小越靠前。
     */
    @TableField("sort_order")
    private Integer sortOrder;

    /**
     * 是否允许基于关键词匹配该分类下的 Prompt。
     */
    @TableField("keyword_match_allowed")
    private Boolean keywordMatchAllowed;

    /**
     * 是否为执行类分类，用于区分执行场景和普通提示词分类。
     */
    @TableField("is_execution_category")
    private Boolean isExecutionCategory;

    /**
     * 分类是否启用。
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * 分类创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 分类最后更新时间。
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
