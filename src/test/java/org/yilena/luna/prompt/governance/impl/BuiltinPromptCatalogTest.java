package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

class BuiltinPromptCatalogTest {

    @Test
    void executionCategoryWithoutTemplateVariablesShouldUseExecutionEditPolicy() {
        PromptItemRecord formatItem = BuiltinPromptCatalog.all().get("format.chat.json_v2");
        Assertions.assertNotNull(formatItem);
        Assertions.assertFalse(Boolean.TRUE.equals(formatItem.getEditPolicy().getCreate()));
        Assertions.assertFalse(Boolean.TRUE.equals(formatItem.getEditPolicy().getDelete()));
    }

    @Test
    void contentCategoryShouldKeepContentEditPolicy() {
        PromptItemRecord personaItem = BuiltinPromptCatalog.all().get("persona.maid.default_v1");
        Assertions.assertNotNull(personaItem);
        Assertions.assertTrue(Boolean.TRUE.equals(personaItem.getEditPolicy().getCreate()));
        Assertions.assertTrue(Boolean.TRUE.equals(personaItem.getEditPolicy().getDelete()));
    }
}
