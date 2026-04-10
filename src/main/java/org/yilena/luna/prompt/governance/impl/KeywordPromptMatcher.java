package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

/**
 * 提示词关键字匹配包装器，负责在提示词解析流程中统一调用关键字匹配逻辑。
 */
final class KeywordPromptMatcher {
    private final KeywordMatcher delegate;
    private final PromptCategoryService promptCategoryService;

    KeywordPromptMatcher(KeywordMatcher delegate, PromptCategoryService promptCategoryService) {
        this.delegate = delegate;
        this.promptCategoryService = promptCategoryService;
    }

    /**
     * 判断提示词是否命中当前用户输入中的关键字。
     */
    boolean matches(PromptItemRecord item, String userInput) {
        return delegate.matches(item, userInput, promptCategoryService);
    }
}
