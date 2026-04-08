package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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
