package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yilena.luna.prompt.governance.support.PromptKeyAliasSupport;

class PromptKeyAliasSupportTest {

    @Test
    void shouldMatchCanonicalLegacyAndShortAliases() {
        Assertions.assertTrue(PromptKeyAliasSupport.matches("repair.main.json_v1", "repair.main_json_v1"));
        Assertions.assertTrue(PromptKeyAliasSupport.matches("repair.main.json_v1", "main_json_v1"));
        Assertions.assertTrue(PromptKeyAliasSupport.matches("agent-local.reconstruction.default_v1", "agent.reconstruction.default_v1"));
        Assertions.assertTrue(PromptKeyAliasSupport.matches("tool.args.default_v1", "tool.args_v1"));
        Assertions.assertTrue(PromptKeyAliasSupport.matches("task.runtime.main_v1", "runtime.main_v1"));
        Assertions.assertTrue(PromptKeyAliasSupport.matches("task.planner.master_v1", "planner.master_v1"));
    }

    @Test
    void aliasesShouldContainCanonicalForLegacyKey() {
        Assertions.assertTrue(PromptKeyAliasSupport.aliasesOf("tool.args_v1").stream()
                .anyMatch(key -> "tool.args.default_v1".equalsIgnoreCase(key)));
        Assertions.assertTrue(PromptKeyAliasSupport.aliasesOf("runtime.main_v1").stream()
                .anyMatch(key -> "task.runtime.main_v1".equalsIgnoreCase(key)));
    }
}
