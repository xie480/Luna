package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class PromptResolverServiceImplTest {

    @Test
    void keywordMatchOnlyAppliesToContentPrompt() {
        PromptItemRecord content = PromptItemRecord.builder()
                .itemId(1L)
                .versionId(11L)
                .key("persona.maid.gentle_v1")
                .value("gentle")
                .category("persona")
                .subCategory("maid")
                .runtimeSlot("instructions.persona")
                .hasTemplateVariables(false)
                .templateVariables(List.of())
                .keywordMatchEnabled(true)
                .matchKeywords(List.of("温柔"))
                .assemblyMode("KEYWORD_ONLY")
                .matchScope(Map.of())
                .editPolicy(Map.of())
                .enabled(true)
                .priority(80)
                .status("active")
                .version("1.0.0")
                .build();
        PromptItemRecord execution = PromptItemRecord.builder()
                .itemId(2L)
                .versionId(22L)
                .key("repair.main_json_v1")
                .value("repair")
                .category("repair")
                .subCategory("json")
                .runtimeSlot("repair.main")
                .hasTemplateVariables(true)
                .templateVariables(List.of("invalidJson"))
                .keywordMatchEnabled(true)
                .matchKeywords(List.of("温柔"))
                .assemblyMode("KEYWORD_ONLY")
                .matchScope(Map.of())
                .editPolicy(Map.of())
                .enabled(true)
                .priority(100)
                .status("active")
                .version("1.0.0")
                .build();
        PromptRegistryService registry = new StubRegistry(List.of(content, execution));
        PromptPolicyService policy = new StubPolicy();
        PromptResolverServiceImpl resolver = new PromptResolverServiceImpl(registry, policy);
        PromptResolveResult result = resolver.resolve(PromptResolveContext.builder()
                .userInput("我想和温柔女仆聊天")
                .agent("MAIN_CHAT_AGENT")
                .nodeKind("CHAT_TURN")
                .taskState("EXECUTING")
                .build());
        Assertions.assertTrue(result.getMatchedItems().stream().anyMatch(item -> "persona.maid.gentle_v1".equals(item.getKey())));
        Assertions.assertTrue(result.getMatchedItems().stream().noneMatch(item -> "repair.main_json_v1".equals(item.getKey())));
    }

    private record StubRegistry(List<PromptItemRecord> rows) implements PromptRegistryService {
        @Override
        public Optional<PromptItemRecord> getByKey(String key) {
            return rows.stream().filter(row -> row.getKey().equalsIgnoreCase(key)).findFirst();
        }

        @Override
        public List<PromptItemRecord> listAllActive() {
            return rows;
        }

        @Override
        public List<PromptItemRecord> listByCategory(String category, String subCategory) {
            return rows;
        }

        @Override
        public List<String> listCategories() {
            return List.of();
        }

        @Override
        public String resolvePromptValue(String key, String fallbackValue) {
            return fallbackValue;
        }
    }

    private static class StubPolicy implements PromptPolicyService {
        @Override
        public Set<String> resolveIncludedPromptKeys(String policyId) {
            return Set.of();
        }

        @Override
        public Set<String> resolveExcludedPromptKeys(String policyId) {
            return Set.of();
        }

        @Override
        public List<String> listPolicyIds() {
            return List.of();
        }
    }
}

