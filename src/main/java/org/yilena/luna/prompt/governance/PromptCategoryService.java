package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.model.PromptCategoryTreeNode;

import java.util.List;
import java.util.Optional;

public interface PromptCategoryService {
    List<PromptCategoryEntity> listEnabledOrdered();

    List<PromptCategoryTreeNode> listEnabledTree();

    Optional<PromptCategoryEntity> findByKey(String categoryKey);

    boolean isExecutionCategory(String categoryKey);

    boolean isKeywordMatchAllowed(String categoryKey);
}
