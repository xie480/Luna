package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.model.EditPolicy;
import org.yilena.luna.prompt.governance.model.MatchScope;
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
                .matchKeywords(List.of("gentle"))
                .assemblyMode("KEYWORD_ONLY")
                .matchScope(MatchScope.empty())
                .editPolicy(EditPolicy.contentDefault())
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
                .matchKeywords(List.of("gentle"))
                .assemblyMode("KEYWORD_ONLY")
                .matchScope(MatchScope.empty())
                .editPolicy(EditPolicy.executionDefault())
                .enabled(true)
                .priority(100)
                .status("active")
                .version("1.0.0")
                .build();
        PromptRegistryService registry = new StubRegistry(List.of(content, execution));
        PromptPolicyService policy = new StubPolicy();
        PromptCategoryService categoryService = new StubCategoryService();
        PromptResolverServiceImpl resolver = new PromptResolverServiceImpl(registry, policy, categoryService);
        PromptResolveResult result = resolver.resolve(PromptResolveContext.builder()
                .userInput("input for gentle keyword match")
                .agent("MAIN_CHAT_AGENT")
                .nodeKind("CHAT_TURN")
                .taskState("EXECUTING")
                .build());
        Assertions.assertTrue(result.getMatchedItems().stream().anyMatch(item -> "persona.maid.gentle_v1".equals(item.getKey())));
        Assertions.assertTrue(result.getMatchedItems().stream().noneMatch(item -> "repair.main_json_v1".equals(item.getKey())));
    }

    @Test
    void alwaysModeShouldNotRequireScopeEvenForExecutionCategory() {
        PromptItemRecord executionAlways = PromptItemRecord.builder()
                .itemId(3L)
                .versionId(33L)
                .key("system.guardrail_v1")
                .value("guard")
                .category("system")
                .subCategory("guardrail")
                .runtimeSlot("instructions.system")
                .hasTemplateVariables(true)
                .templateVariables(List.of("runtimePromptInput"))
                .keywordMatchEnabled(false)
                .matchKeywords(List.of())
                .assemblyMode("ALWAYS")
                .matchScope(MatchScope.empty())
                .editPolicy(EditPolicy.executionDefault())
                .enabled(true)
                .priority(120)
                .status("active")
                .version("1.0.0")
                .build();
        PromptResolverServiceImpl resolver = new PromptResolverServiceImpl(
                new StubRegistry(List.of(executionAlways)),
                new StubPolicy(),
                new StubCategoryService()
        );
        PromptResolveResult result = resolver.resolve(PromptResolveContext.builder()
                .userInput("hello")
                .agent("MAIN_CHAT_AGENT")
                .nodeKind("CHAT_TURN")
                .taskState("EXECUTING")
                .build());
        Assertions.assertTrue(result.getMatchedItems().stream().anyMatch(item -> "system.guardrail_v1".equals(item.getKey())));
    }

    @Test
    void keywordOnlyShouldRespectScopeConstraintWhenScopeConfigured() {
        PromptItemRecord item = PromptItemRecord.builder()
                .itemId(4L)
                .versionId(44L)
                .key("persona.keyword_only_scope_ignored")
                .value("v")
                .category("persona")
                .subCategory("maid")
                .runtimeSlot("instructions.persona")
                .hasTemplateVariables(false)
                .templateVariables(List.of())
                .keywordMatchEnabled(true)
                .matchKeywords(List.of("gentle"))
                .assemblyMode("KEYWORD_ONLY")
                .matchScope(MatchScope.builder().agents(List.of("OTHER_AGENT")).build())
                .editPolicy(EditPolicy.contentDefault())
                .enabled(true)
                .priority(80)
                .status("enabled")
                .version("1.0.0")
                .build();

        PromptResolverServiceImpl resolver = new PromptResolverServiceImpl(
                new StubRegistry(List.of(item)),
                new StubPolicy(),
                new StubCategoryService()
        );
        PromptResolveResult result = resolver.resolve(PromptResolveContext.builder()
                .userInput("gentle and calm")
                .agent("MAIN_CHAT_AGENT")
                .build());
        Assertions.assertTrue(result.getMatchedItems().stream()
                .noneMatch(row -> "persona.keyword_only_scope_ignored".equals(row.getKey())));
        Assertions.assertTrue(result.getRejectedItems().stream()
                .anyMatch(row -> "persona.keyword_only_scope_ignored".equals(row.getKey())
                        && "SCOPE_NOT_MATCHED".equals(row.getRejectedReason())));
    }

    @Test
    void keywordOrAgentShouldNotRequireScopeConstraint() {
        PromptItemRecord item = PromptItemRecord.builder()
                .itemId(5L)
                .versionId(55L)
                .key("persona.keyword_or_agent_without_scope")
                .value("v")
                .category("persona")
                .subCategory("maid")
                .runtimeSlot("instructions.persona")
                .hasTemplateVariables(false)
                .templateVariables(List.of())
                .keywordMatchEnabled(true)
                .matchKeywords(List.of("gentle"))
                .assemblyMode("KEYWORD_OR_AGENT")
                .matchScope(MatchScope.empty())
                .editPolicy(EditPolicy.contentDefault())
                .enabled(true)
                .priority(80)
                .status("enabled")
                .version("1.0.0")
                .build();

        PromptResolverServiceImpl resolver = new PromptResolverServiceImpl(
                new StubRegistry(List.of(item)),
                new StubPolicy(),
                new StubCategoryService()
        );
        PromptResolveResult result = resolver.resolve(PromptResolveContext.builder()
                .userInput("gentle and calm")
                .agent("MAIN_CHAT_AGENT")
                .build());
        Assertions.assertTrue(result.getMatchedItems().stream()
                .anyMatch(row -> "persona.keyword_or_agent_without_scope".equals(row.getKey())));
    }

    @Test
    void keywordOrAgentShouldNotMatchWhenNoKeywordAndNoScope() {
        PromptItemRecord item = PromptItemRecord.builder()
                .itemId(6L)
                .versionId(66L)
                .key("persona.keyword_or_agent_no_scope_no_keyword")
                .value("v")
                .category("persona")
                .subCategory("maid")
                .runtimeSlot("instructions.persona")
                .hasTemplateVariables(false)
                .templateVariables(List.of())
                .keywordMatchEnabled(true)
                .matchKeywords(List.of("gentle"))
                .assemblyMode("KEYWORD_OR_AGENT")
                .matchScope(MatchScope.empty())
                .editPolicy(EditPolicy.contentDefault())
                .enabled(true)
                .priority(80)
                .status("enabled")
                .version("1.0.0")
                .build();

        PromptResolverServiceImpl resolver = new PromptResolverServiceImpl(
                new StubRegistry(List.of(item)),
                new StubPolicy(),
                new StubCategoryService()
        );
        PromptResolveResult result = resolver.resolve(PromptResolveContext.builder()
                .userInput("nothing related")
                .agent("MAIN_CHAT_AGENT")
                .build());
        Assertions.assertTrue(result.getMatchedItems().stream()
                .noneMatch(row -> "persona.keyword_or_agent_no_scope_no_keyword".equals(row.getKey())));
        Assertions.assertTrue(result.getRejectedItems().stream()
                .anyMatch(row -> "persona.keyword_or_agent_no_scope_no_keyword".equals(row.getKey())
                        && "KEYWORD_OR_SCOPE_NOT_MATCHED".equals(row.getRejectedReason())));
    }

    private record StubRegistry(List<PromptItemRecord> rows) implements PromptRegistryService {
        @Override
        public Optional<PromptItemRecord> getByKey(String key) {
            return rows.stream().filter(row -> row.getKey().equalsIgnoreCase(key)).findFirst();
        }

        @Override
        public Optional<PromptItemRecord> getByKeyIncludingDisabled(String key) {
            return getByKey(key);
        }

        @Override
        public Optional<PromptItemRecord> getById(Long id) {
            return rows.stream().filter(row -> row.getItemId() != null && row.getItemId().equals(id)).findFirst();
        }

        @Override
        public Optional<PromptItemRecord> getByIdIncludingDisabled(Long id) {
            return getById(id);
        }

        @Override
        public boolean existsByKey(String key) {
            return getByKey(key).isPresent();
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
        public Map<String, String> listKeyValueByCategory(String category) {
            return Map.of();
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
        public org.yilena.luna.prompt.governance.entity.PromptPolicyEntity getByPolicyId(String policyId) {
            return null;
        }

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

    private static class StubCategoryService implements PromptCategoryService {
        @Override
        public List<PromptCategoryEntity> listEnabledOrdered() {
            return List.of();
        }

        @Override
        public Optional<PromptCategoryEntity> findByKey(String categoryKey) {
            return Optional.empty();
        }

        @Override
        public boolean isExecutionCategory(String categoryKey) {
            return "repair".equalsIgnoreCase(categoryKey)
                    || "tool".equalsIgnoreCase(categoryKey)
                    || "summary".equalsIgnoreCase(categoryKey)
                    || "agent-local".equalsIgnoreCase(categoryKey)
                    || "task".equalsIgnoreCase(categoryKey)
                    || "system".equalsIgnoreCase(categoryKey);
        }

        @Override
        public boolean isKeywordMatchAllowed(String categoryKey) {
            return !isExecutionCategory(categoryKey);
        }
    }
}
