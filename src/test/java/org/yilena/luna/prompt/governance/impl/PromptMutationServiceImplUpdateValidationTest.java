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
                        .promptKey("persona.policy.locked_v1")
                        .category("persona")
                        .subCategory("policy")
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
        request.setKey("persona.policy.locked_v1");
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
                        .promptKey("repair.main.exec_v1")
                        .category("repair")
                        .subCategory("main")
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
        Mockito.when(registryService.getByKey("repair.main.exec_v1")).thenReturn(Optional.of(sampleRecord("repair.main.exec_v1")));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("repair.main.exec_v1");
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
                        .promptKey("summary.agent.default_v1")
                        .category("summary")
                        .subCategory("agent")
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
        Mockito.when(registryService.getByKey("summary.agent.default_v1")).thenReturn(Optional.of(sampleRecord("summary.agent.default_v1")));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("summary.agent.default_v1");
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
                        .promptKey("persona.maid.gentle_v1")
                        .category("persona")
                        .subCategory("maid")
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
        request.setKey("persona.maid.gentle_v1");
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
                        .promptKey("persona.template.forbidden_v1")
                        .category("persona")
                        .subCategory("template")
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
        request.setKey("persona.template.forbidden_v1");
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
                        .promptKey("persona.placeholder.forbidden_v1")
                        .category("persona")
                        .subCategory("placeholder")
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
        request.setKey("persona.placeholder.forbidden_v1");
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
                        .promptKey("persona.mode.forbidden_v1")
                        .category("persona")
                        .subCategory("mode")
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
        request.setKey("persona.mode.forbidden_v1");
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
                        .promptKey("repair.main.exec_v1")
                        .category("repair")
                        .subCategory("main")
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
        request.setKey("repair.main.exec_v1");
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
                        .promptKey("tool.agent.decision_v1")
                        .category("tool")
                        .subCategory("agent")
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
        Mockito.when(categoryService.isExecutionCategory("tool")).thenReturn(true);
        Mockito.when(registryService.getByKey("tool.agent.decision_v1")).thenReturn(Optional.of(sampleRecord("tool.agent.decision_v1")));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("tool.agent.decision_v1");
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
    void updateShouldSaveDraftWithoutActivateWhenExecutionPreviewOnly() {
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
                        .id(702L)
                        .promptKey("repair.main.exec_v1")
                        .category("repair")
                        .subCategory("main")
                        .hasTemplateVariables(true)
                        .currentVersionId(703L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(703L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(703L)
                        .templateVariables(List.of("invalidJson"))
                        .editPolicy(Map.of("create", false, "update", true, "delete", false))
                        .build());
        Mockito.when(categoryService.isExecutionCategory("repair")).thenReturn(true);
        Mockito.when(registryService.getByKey("repair.main.exec_v1")).thenReturn(Optional.of(sampleRecord("repair.main.exec_v1")));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("repair.main.exec_v1");
        request.setValue("new value");
        request.setPreviewOnly(true);

        service.update(request);

        ArgumentCaptor<PromptItemVersionEntity> versionCaptor = ArgumentCaptor.forClass(PromptItemVersionEntity.class);
        Mockito.verify(versionMapper).insert(versionCaptor.capture());
        PromptItemVersionEntity saved = versionCaptor.getValue();
        Assertions.assertEquals("draft", saved.getStatus());
        Assertions.assertEquals(false, saved.getIsActive());
        Mockito.verify(promptVersionService, Mockito.never()).activateVersion(Mockito.anyLong());
    }

    @Test
    void updateShouldAllowExecutionTemplateVariablesEvolution() {
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
                        .id(880L)
                        .promptKey("repair.main.exec_v1")
                        .category("repair")
                        .subCategory("main")
                        .hasTemplateVariables(true)
                        .currentVersionId(881L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(881L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(881L)
                        .versionNo("1.0.0")
                        .templateVariables(List.of("invalidJson"))
                        .editPolicy(Map.of("create", false, "update", true, "delete", false))
                        .build());
        Mockito.when(categoryService.isExecutionCategory("repair")).thenReturn(true);
        Mockito.when(registryService.getByKey("repair.main.exec_v1")).thenReturn(Optional.of(sampleRecord("repair.main.exec_v1")));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("repair.main.exec_v1");
        request.setValue("new value");
        request.setTemplateVariables(List.of("invalidJsonV2", "invalidJsonV2", "repairSeed"));

        service.update(request);

        ArgumentCaptor<PromptItemVersionEntity> versionCaptor = ArgumentCaptor.forClass(PromptItemVersionEntity.class);
        Mockito.verify(versionMapper).insert(versionCaptor.capture());
        Assertions.assertEquals(List.of("invalidJsonV2", "repairSeed"), versionCaptor.getValue().getTemplateVariables());
    }

    @Test
    void updateShouldRejectExecutionTemplateVariableWithInvalidName() {
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
                        .id(882L)
                        .promptKey("repair.main.exec_v1")
                        .category("repair")
                        .subCategory("main")
                        .hasTemplateVariables(true)
                        .currentVersionId(883L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(883L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(883L)
                        .templateVariables(List.of("invalidJson"))
                        .editPolicy(Map.of("create", false, "update", true, "delete", false))
                        .build());
        Mockito.when(categoryService.isExecutionCategory("repair")).thenReturn(true);
        Mockito.when(registryService.getByKey("repair.main.exec_v1")).thenReturn(Optional.of(sampleRecord("repair.main.exec_v1")));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("repair.main.exec_v1");
        request.setTemplateVariables(List.of("bad-name"));

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(request));
        Assertions.assertTrue(ex.getMessage().contains("invalid variable name"));
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
                        .promptKey("persona.test.demo_v1")
                        .category("persona")
                        .subCategory("test")
                        .hasTemplateVariables(false)
                        .currentVersionId(null)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(categoryService.isExecutionCategory("persona")).thenReturn(false);

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.deleteByKey("persona.test.demo_v1"));
        Assertions.assertTrue(ex.getMessage().contains("prompt delete policy denied"));
        Mockito.verify(itemMapper, Mockito.never()).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void updateShouldAllowLegacyKeyWhenPromptAlreadyExists() {
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
                        .id(990L)
                        .promptKey("memory-hint.default_v1")
                        .category("memory-hint")
                        .subCategory("default")
                        .hasTemplateVariables(false)
                        .currentVersionId(991L)
                        .enabled(true)
                        .status("enabled")
                        .build());
        Mockito.when(versionMapper.selectById(991L))
                .thenReturn(PromptItemVersionEntity.builder()
                        .id(991L)
                        .templateVariables(List.of())
                        .editPolicy(Map.of("create", true, "update", true, "delete", true))
                        .build());
        Mockito.when(categoryService.isExecutionCategory("memory-hint")).thenReturn(false);
        Mockito.when(registryService.getByKey("memory-hint.default_v1"))
                .thenReturn(Optional.of(PromptItemRecord.builder()
                        .itemId(990L)
                        .versionId(992L)
                        .key("memory-hint.default_v1")
                        .name("memory-hint.default_v1")
                        .value("new value")
                        .category("memory-hint")
                        .subCategory("default")
                        .runtimeSlot("memory.hints")
                        .hasTemplateVariables(false)
                        .templateVariables(List.of())
                        .keywordMatchEnabled(false)
                        .matchKeywords(List.of())
                        .assemblyMode("KEYWORD_ONLY")
                        .matchScope(MatchScope.empty())
                        .editPolicy(EditPolicy.contentDefault())
                        .enabled(true)
                        .priority(80)
                        .status("enabled")
                        .version("1.0.1")
                        .versionLabel("1.0.1")
                        .changeNote("")
                        .build()));

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("memory-hint.default_v1");
        request.setValue("new value");

        service.update(request);

        Mockito.verify(itemMapper).update(Mockito.isNull(), Mockito.any());
        Mockito.verify(versionMapper).insert(Mockito.any(PromptItemVersionEntity.class));
        Mockito.verify(promptVersionService).activateVersion(Mockito.anyLong());
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

