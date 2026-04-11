package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.model.PromptCategoryTreeNode;

import java.util.List;
import java.util.Optional;

/**
 * 提示词分类服务接口，负责管理提示词分类的查询、树形组织和分类能力判断，
 * 为前台分类展示和提示词治理校验提供分类基础数据。
 */
public interface PromptCategoryService {
    List<PromptCategoryEntity> listEnabledOrdered();

    List<PromptCategoryTreeNode> listEnabledTree();

    Optional<PromptCategoryEntity> findByKey(String categoryKey);

    boolean isExecutionCategory(String categoryKey);

    boolean isKeywordMatchAllowed(String categoryKey);
}
