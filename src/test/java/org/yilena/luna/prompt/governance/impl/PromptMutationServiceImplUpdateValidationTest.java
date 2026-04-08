package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptVersionService;
import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;
import org.yilena.luna.prompt.governance.model.EditPolicy;
import org.yilena.luna.prompt.governance.model.MatchScope;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class PromptMutationServiceImplUpdateValidationTest {

    @Test
    void updateShouldRejectWhenEditPolicyUpdateDenied() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptRegistryService.class),
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(600L)
                        .promptKey("persona.policy.locked")
                        .category("persona")
                        .hasTemplateVariables(false)
                        .currentVersionId(601L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(601L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(601L)
                        .templateVariables(List.of())
                        .editPolicy(Map.of("create", true, "update", false, "delete", true))
                        .build());

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("persona.policy.locked");
        request.setValue("new value");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(request));
        Assertions.assertTrue(ex.getMessage().contains("prompt update policy denied"));
        Mockito.verify(itemMapper, Mockito.never()).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void updateShouldRejectDisablingExecutionPromptByEnabledFlag() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptRegistryService registryService = Mockito.mock(PromptRegistryService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptVersionService.class),
                registryService,
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(100L)
                        .promptKey("agent.repair.main")
                        .category("repair")
                        .hasTemplateVariables(true)
                        .currentVersionId(200L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(200L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(200L)
                        .templateVariables(List.of("invalidJson"))
                        .editPolicy(Map.of("create", false, "update", true, "delete", false))
                        .build());
        Mockito.when(categoryService.isExecutionCategory("repair")).thenReturn(true);
        Mockito.when(registryService.getByKey("agent.repair.main")).thenReturn(Optional.of(sampleRecord("agent.repair.main")));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("agent.repair.main");
        request.setEnabled(false);

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(request));
        Assertions.assertTrue(ex.getMessage().contains("execution prompt cannot be disabled"));
    }

    @Test
    void updateShouldRejectDisablingExecutionPromptByStatus() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptRegistryService registryService = Mockito.mock(PromptRegistryService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptVersionService.class),
                registryService,
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(101L)
                        .promptKey("agent.summary.default")
                        .category("summary")
                        .hasTemplateVariables(false)
                        .currentVersionId(201L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(201L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(201L)
                        .templateVariables(List.of())
                        .editPolicy(Map.of("create", false, "update", true, "delete", false))
                        .build());
        Mockito.when(categoryService.isExecutionCategory("summary")).thenReturn(true);
        Mockito.when(registryService.getByKey("agent.summary.default")).thenReturn(Optional.of(sampleRecord("agent.summary.default")));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("agent.summary.default");
        request.setStatus("disabled");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(request));
        Assertions.assertTrue(ex.getMessage().contains("execution prompt status cannot be set to disabled"));
    }

    @Test
    void updateShouldRejectUnknownCategory() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptRegistryService.class),
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(300L)
                        .promptKey("persona.maid.gentle")
                        .category("persona")
                        .hasTemplateVariables(false)
                        .currentVersionId(301L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(301L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(301L)
                        .templateVariables(List.of())
                        .build());
        Mockito.when(categoryService.findByKey("not-registered")).thenReturn(Optional.empty());
        Mockito.when(categoryService.isExecutionCategory("persona")).thenReturn(false);

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("persona.maid.gentle");
        request.setCategory("not-registered");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(request));
        Assertions.assertTrue(ex.getMessage().contains("category must exist in prompt_category"));
    }

    @Test
    void updateShouldRejectTemplateVariablesForContentPrompt() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptRegistryService.class),
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(330L)
                        .promptKey("persona.template.forbidden")
                        .category("persona")
                        .hasTemplateVariables(false)
                        .currentVersionId(331L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(331L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(331L)
                        .templateVariables(List.of())
                        .build());
        Mockito.when(categoryService.isExecutionCategory("persona")).thenReturn(false);

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("persona.template.forbidden");
        request.setTemplateVariables(List.of("userName"));

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(request));
        Assertions.assertTrue(ex.getMessage().contains("content prompt update cannot carry templateVariables"));
        Mockito.verify(itemMapper, Mockito.never()).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void updateShouldRejectTemplatePlaceholderForContentPrompt() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptRegistryService.class),
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(332L)
                        .promptKey("persona.placeholder.forbidden")
                        .category("persona")
                        .hasTemplateVariables(false)
                        .currentVersionId(333L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(333L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(333L)
                        .templateVariables(List.of())
                        .build());
        Mockito.when(categoryService.isExecutionCategory("persona")).thenReturn(false);

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("persona.placeholder.forbidden");
        request.setValue("hello ${user_name}");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(request));
        Assertions.assertTrue(ex.getMessage().contains("content prompt update value cannot carry template placeholder"));
        Mockito.verify(itemMapper, Mockito.never()).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void updateShouldRejectExecutionAssemblyModeForContentPrompt() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptRegistryService.class),
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(334L)
                        .promptKey("persona.mode.forbidden")
                        .category("persona")
                        .hasTemplateVariables(false)
                        .currentVersionId(335L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(335L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(335L)
                        .templateVariables(List.of())
                        .build());
        Mockito.when(categoryService.isExecutionCategory("persona")).thenReturn(false);

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("persona.mode.forbidden");
        request.setAssemblyMode("AGENT_ONLY");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(request));
        Assertions.assertTrue(ex.getMessage().contains("content prompt assembly_mode is not allowed"));
        Mockito.verify(itemMapper, Mockito.never()).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void updateShouldRejectMigratingExecutionCategoryToContentCategory() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptRegistryService.class),
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(320L)
                        .promptKey("agent.repair.main")
                        .category("repair")
                        .hasTemplateVariables(false)
                        .currentVersionId(321L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(321L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(321L)
                        .templateVariables(List.of())
                        .build());
        Mockito.when(categoryService.findByKey("persona"))
                .thenReturn(Optional.of(PromptCategoryEntity.builder()
                        .categoryKey("persona")
                        .isExecutionCategory(false)
                        .build()));
        Mockito.when(categoryService.isExecutionCategory("repair")).thenReturn(true);
        Mockito.when(categoryService.isExecutionCategory("persona")).thenReturn(false);

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("agent.repair.main");
        request.setCategory("persona");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(request));
        Assertions.assertTrue(ex.getMessage().contains("execution prompt category cannot be migrated to content category"));
        Mockito.verify(itemMapper, Mockito.never()).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void updateShouldKeepExecutionStructureFieldsWhenRequestFieldsMissing() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptRegistryService registryService = Mockito.mock(PromptRegistryService.class);
        PromptVersionService promptVersionService = Mockito.mock(PromptVersionService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                promptVersionService,
                registryService,
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(700L)
                        .promptKey("agent.tool.decision")
                        .category("tooling")
                        .hasTemplateVariables(true)
                        .currentVersionId(701L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(701L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(701L)
                        .templateVariables(List.of("runtimePromptInput"))
                        .matchKeywords(List.of("tool", "decision"))
                        .matchScope(Map.of("agents", List.of("TOOL_DECISION_AGENT")))
                        .editPolicy(Map.of("create", false, "update", true, "delete", false))
                        .build());
        Mockito.when(categoryService.isExecutionCategory("tooling")).thenReturn(true);
        Mockito.when(registryService.getByKey("agent.tool.decision")).thenReturn(Optional.of(sampleRecord("agent.tool.decision")));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("agent.tool.decision");
        request.setValue("new value");

        service.update(request);

        ArgumentCaptor<PromptItemVersionEntity> versionCaptor = ArgumentCaptor.forClass(PromptItemVersionEntity.class);
        Mockito.verify(versionMapper).insert(versionCaptor.capture());
        PromptItemVersionEntity saved = versionCaptor.getValue();
        Assertions.assertEquals(List.of("runtimePromptInput"), saved.getTemplateVariables());
        Assertions.assertEquals(List.of("tool", "decision"), saved.getMatchKeywords());
        Assertions.assertEquals(List.of("TOOL_DECISION_AGENT"), saved.getMatchScope().get("agents"));
    }

    @Test
    void deleteShouldRejectWhenCurrentVersionMissing() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptRegistryService.class),
                categoryService
        );

        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(800L)
                        .promptKey("persona.test")
                        .category("persona")
                        .hasTemplateVariables(false)
                        .currentVersionId(null)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(categoryService.isExecutionCategory("persona")).thenReturn(false);

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.deleteByKey("persona.test"));
        Assertions.assertTrue(ex.getMessage().contains("prompt delete policy denied"));
        Mockito.verify(itemMapper, Mockito.never()).update(Mockito.isNull(), Mockito.any());
    }

    private PromptItemRecord sampleRecord(String key) {
        return PromptItemRecord.builder()
                .itemId(1L)
                .versionId(1L)
                .key(key)
                .name(key)
                .value("v")
                .category("repair")
                .subCategory("default")
                .runtimeSlot("runtime.prompt")
                .hasTemplateVariables(true)
                .templateVariables(List.of())
                .keywordMatchEnabled(false)
                .matchKeywords(List.of())
                .assemblyMode("AGENT_ONLY")
                .matchScope(MatchScope.empty())
                .editPolicy(EditPolicy.executionDefault())
                .enabled(true)
                .priority(100)
                .status("enabled")
                .version("1.0.0")
                .versionLabel("1.0.0")
                .changeNote("")
                .build();
    }
}
