package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;

final class KeywordMatcher {

    boolean matches(PromptItemRecord item, String userInput, PromptCategoryService promptCategoryService) {
        if (item == null || promptCategoryService == null) {
            return false;
        }
        if (!promptCategoryService.isKeywordMatchAllowed(item.getCategory())) {
            return false;
        }
        if (item.isHasTemplateVariables()) {
            return false;
        }
        if (!item.isKeywordMatchEnabled()) {
            return false;
        }
        List<String> keywords = item.getMatchKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        String input = userInput == null ? "" : userInput.toLowerCase();
        if (input.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && input.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
