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
public class PromptCategoryTreeNode {

    private String categoryKey;

    private String categoryName;

    private String parentCategoryKey;

    private Integer sortOrder;

    private Boolean keywordMatchAllowed;

    private Boolean executionCategory;

    private Boolean enabled;

    private List<PromptCategoryTreeNode> children;
}
