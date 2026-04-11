package org.yilena.luna.prompt.governance.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * 提示词分类树节点模型，负责表示治理后台中的分类层级结构，
 * 用于前端展示提示词分类树及其可用状态。
 */
public class PromptCategoryTreeNode {

    /**
     * 当前分类键。
     */
    private String categoryKey;

    /**
     * 当前分类名称。
     */
    private String categoryName;

    /**
     * 父级分类键。
     */
    private String parentCategoryKey;

    /**
     * 分类排序值，值越小越靠前。
     */
    private Integer sortOrder;

    /**
     * 是否允许关键字匹配。
     */
    private Boolean keywordMatchAllowed;

    /**
     * 是否属于执行类分类。
     */
    private Boolean executionCategory;

    /**
     * 分类是否启用。
     */
    private Boolean enabled;

    /**
     * 子分类节点列表。
     */
    private List<PromptCategoryTreeNode> children;
}
