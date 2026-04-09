package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

final class KeywordPromptMatcher {
    private final KeywordMatcher delegate;
    private final PromptCategoryService promptCategoryService;

    KeywordPromptMatcher(KeywordMatcher delegate, PromptCategoryService promptCategoryService) {
        this.delegate = delegate;
        this.promptCategoryService = promptCategoryService;
    }

    boolean matches(PromptItemRecord item, String userInput) {
        return delegate.matches(item, userInput, promptCategoryService);
    }
}
