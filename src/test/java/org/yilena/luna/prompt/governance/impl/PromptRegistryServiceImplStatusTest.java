package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

class PromptRegistryServiceImplStatusTest {

    @Test
    void itemActiveJudgeShouldTreatOnlyEnabledAndBlankAsActive() throws Exception {
        PromptRegistryServiceImpl service = new PromptRegistryServiceImpl(
                Mockito.mock(PromptItemMapper.class),
                Mockito.mock(PromptItemVersionMapper.class),
                Mockito.mock(PromptCategoryService.class)
        );

        Method isItemActive = PromptRegistryServiceImpl.class.getDeclaredMethod("isItemActive", PromptItemEntity.class);
        isItemActive.setAccessible(true);

        PromptItemEntity enabled = PromptItemEntity.builder().enabled(true).status("enabled").build();
        PromptItemEntity blank = PromptItemEntity.builder().enabled(true).status("").build();
        PromptItemEntity active = PromptItemEntity.builder().enabled(true).status("active").build();
        PromptItemEntity disabled = PromptItemEntity.builder().enabled(true).status("disabled").build();

        Assertions.assertEquals(true, isItemActive.invoke(service, enabled));
        Assertions.assertEquals(true, isItemActive.invoke(service, blank));
        Assertions.assertEquals(false, isItemActive.invoke(service, active));
        Assertions.assertEquals(false, isItemActive.invoke(service, disabled));
    }

    @Test
    void getByKeyIncludingDisabledShouldReturnArchivedVersionForGovernance() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptRegistryServiceImpl service = new PromptRegistryServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptCategoryService.class)
        );
        PromptItemEntity item = PromptItemEntity.builder()
                .id(10L)
                .promptKey("persona.archived.demo")
                .category("persona")
                .enabled(false)
                .status("disabled")
                .currentVersionId(100L)
                .build();
        PromptItemVersionEntity archivedCurrent = PromptItemVersionEntity.builder()
                .id(100L)
                .promptItemId(10L)
                .versionNo("1.2.0")
                .promptValue("archived value")
                .isActive(false)
                .status("archived")
                .build();
        Mockito.when(itemMapper.selectOne(Mockito.any())).thenReturn(item);
        Mockito.when(versionMapper.selectById(100L)).thenReturn(archivedCurrent);

        Optional<PromptItemRecord> found = service.getByKeyIncludingDisabled("persona.archived.demo");
        Assertions.assertTrue(found.isPresent());
        Assertions.assertEquals("1.2.0", found.get().getVersion());
        Assertions.assertEquals("archived value", found.get().getValue());
    }

    @Test
    void getByKeyIncludingDisabledShouldFallbackToLatestVersionWhenCurrentMissing() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptRegistryServiceImpl service = new PromptRegistryServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptCategoryService.class)
        );
        PromptItemEntity item = PromptItemEntity.builder()
                .id(12L)
                .promptKey("persona.disabled.no-current")
                .category("persona")
                .enabled(false)
                .status("disabled")
                .currentVersionId(null)
                .build();
        PromptItemVersionEntity latestArchived = PromptItemVersionEntity.builder()
                .id(120L)
                .promptItemId(12L)
                .versionNo("2.0.0")
                .promptValue("latest archived value")
                .isActive(false)
                .status("archived")
                .build();
        Mockito.when(itemMapper.selectOne(Mockito.any())).thenReturn(item);
        Mockito.when(versionMapper.selectList(Mockito.any())).thenReturn(List.of(latestArchived));

        Optional<PromptItemRecord> found = service.getByKeyIncludingDisabled("persona.disabled.no-current");
        Assertions.assertTrue(found.isPresent());
        Assertions.assertEquals("2.0.0", found.get().getVersion());
        Assertions.assertEquals("latest archived value", found.get().getValue());
    }

    @Test
    void existsByKeyShouldNotDependOnActiveVersion() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptRegistryServiceImpl service = new PromptRegistryServiceImpl(
                itemMapper,
                versionMapper,
                Mockito.mock(PromptCategoryService.class)
        );
        Mockito.when(itemMapper.selectOne(Mockito.any()))
                .thenReturn(PromptItemEntity.builder()
                        .id(11L)
                        .promptKey("repair.deleted.demo")
                        .enabled(false)
                        .status("disabled")
                        .currentVersionId(null)
                        .build());
        Mockito.when(versionMapper.selectList(Mockito.any())).thenReturn(List.of());

        Assertions.assertTrue(service.existsByKey("repair.deleted.demo"));
    }
}
