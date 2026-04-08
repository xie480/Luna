package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.dto.PromptPolicySaveRequest;
import org.yilena.luna.prompt.governance.entity.PromptPolicyEntity;
import org.yilena.luna.prompt.governance.entity.PromptPolicyVersionEntity;
import org.yilena.luna.prompt.governance.mapper.PromptPolicyMapper;
import org.yilena.luna.prompt.governance.mapper.PromptPolicyVersionMapper;

import java.util.List;
import java.util.Set;

class PromptPolicyServiceImplTest {

    @Test
    void resolveIncludedPromptKeysShouldIgnoreNonActiveCurrentVersion() {
        PromptPolicyMapper policyMapper = Mockito.mock(PromptPolicyMapper.class);
        PromptPolicyVersionMapper versionMapper = Mockito.mock(PromptPolicyVersionMapper.class);
        PromptPolicyServiceImpl service = new PromptPolicyServiceImpl(policyMapper, versionMapper);

        Mockito.when(policyMapper.selectOne(Mockito.any()))
                .thenReturn(PromptPolicyEntity.builder()
                        .id(10L)
                        .policyKey("default")
                        .enabled(true)
                        .currentVersionId(100L)
                        .build());
        Mockito.when(versionMapper.selectOne(Mockito.any())).thenReturn(null);

        Set<String> included = service.resolveIncludedPromptKeys("default");
        Assertions.assertTrue(included.isEmpty());
        Mockito.verify(versionMapper, Mockito.never()).selectById(Mockito.anyLong());
    }

    @Test
    void resolveIncludedPromptKeysShouldReturnActiveCurrentVersion() {
        PromptPolicyMapper policyMapper = Mockito.mock(PromptPolicyMapper.class);
        PromptPolicyVersionMapper versionMapper = Mockito.mock(PromptPolicyVersionMapper.class);
        PromptPolicyServiceImpl service = new PromptPolicyServiceImpl(policyMapper, versionMapper);

        Mockito.when(policyMapper.selectOne(Mockito.any()))
                .thenReturn(PromptPolicyEntity.builder()
                        .id(11L)
                        .policyKey("stable")
                        .enabled(true)
                        .currentVersionId(110L)
                        .build());
        Mockito.when(versionMapper.selectOne(Mockito.any()))
                .thenReturn(PromptPolicyVersionEntity.builder()
                        .id(110L)
                        .promptPolicyId(11L)
                        .status("active")
                        .isActive(true)
                        .includePromptKeys(List.of("persona.default_v1", "repair.main_json_v1"))
                        .build());

        Set<String> included = service.resolveIncludedPromptKeys("stable");
        Assertions.assertEquals(Set.of("persona.default_v1", "repair.main_json_v1"), included);
    }

    @Test
    void savePolicyShouldArchiveOldActiveVersion() {
        PromptPolicyMapper policyMapper = Mockito.mock(PromptPolicyMapper.class);
        PromptPolicyVersionMapper versionMapper = Mockito.mock(PromptPolicyVersionMapper.class);
        PromptPolicyServiceImpl service = new PromptPolicyServiceImpl(policyMapper, versionMapper);

        PromptPolicyEntity existing = PromptPolicyEntity.builder()
                .id(12L)
                .policyKey("baseline")
                .policyName("baseline")
                .description("")
                .enabled(true)
                .currentVersionId(120L)
                .build();
        Mockito.when(policyMapper.selectOne(Mockito.any())).thenReturn(existing);
        Mockito.when(policyMapper.selectById(12L)).thenReturn(existing);

        PromptPolicySaveRequest request = new PromptPolicySaveRequest();
        request.setPolicyId("baseline");
        request.setVersion("1.0.1");
        request.setIncludePromptKeys(List.of("persona.default_v1"));
        request.setExcludePromptKeys(List.of());

        service.savePolicy(request);

        ArgumentCaptor<LambdaUpdateWrapper> versionUpdateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(versionMapper).update(Mockito.isNull(), versionUpdateCaptor.capture());
        String sqlSet = versionUpdateCaptor.getValue().getSqlSet();
        Assertions.assertNotNull(sqlSet);
        Assertions.assertTrue(sqlSet.contains("is_active"));
        Assertions.assertTrue(sqlSet.contains("status"));

        ArgumentCaptor<PromptPolicyVersionEntity> insertCaptor = ArgumentCaptor.forClass(PromptPolicyVersionEntity.class);
        Mockito.verify(versionMapper).insert(insertCaptor.capture());
        PromptPolicyVersionEntity inserted = insertCaptor.getValue();
        Assertions.assertEquals("active", inserted.getStatus());
        Assertions.assertEquals(true, inserted.getIsActive());
    }

    @Test
    void listPolicyVersionsShouldQueryByPolicyId() {
        PromptPolicyMapper policyMapper = Mockito.mock(PromptPolicyMapper.class);
        PromptPolicyVersionMapper versionMapper = Mockito.mock(PromptPolicyVersionMapper.class);
        PromptPolicyServiceImpl service = new PromptPolicyServiceImpl(policyMapper, versionMapper);

        Mockito.when(policyMapper.selectOne(Mockito.any()))
                .thenReturn(PromptPolicyEntity.builder()
                        .id(20L)
                        .policyKey("stable")
                        .enabled(true)
                        .build());
        Mockito.when(versionMapper.selectList(Mockito.any()))
                .thenReturn(List.of(PromptPolicyVersionEntity.builder().id(201L).promptPolicyId(20L).build()));

        List<PromptPolicyVersionEntity> versions = service.listPolicyVersions("stable");
        Assertions.assertEquals(1, versions.size());
        Assertions.assertEquals(201L, versions.get(0).getId());
    }

    @Test
    void activatePolicyVersionShouldSwitchCurrentVersion() {
        PromptPolicyMapper policyMapper = Mockito.mock(PromptPolicyMapper.class);
        PromptPolicyVersionMapper versionMapper = Mockito.mock(PromptPolicyVersionMapper.class);
        PromptPolicyServiceImpl service = new PromptPolicyServiceImpl(policyMapper, versionMapper);

        Mockito.when(policyMapper.selectOne(Mockito.any()))
                .thenReturn(PromptPolicyEntity.builder()
                        .id(30L)
                        .policyKey("baseline")
                        .enabled(true)
                        .currentVersionId(301L)
                        .build());
        Mockito.when(versionMapper.selectById(302L))
                .thenReturn(PromptPolicyVersionEntity.builder()
                        .id(302L)
                        .promptPolicyId(30L)
                        .status("archived")
                        .isActive(false)
                        .build());

        service.activatePolicyVersion("baseline", 302L);

        Mockito.verify(versionMapper, Mockito.times(2)).update(Mockito.isNull(), Mockito.any(LambdaUpdateWrapper.class));
        Mockito.verify(policyMapper).update(Mockito.isNull(), Mockito.any(LambdaUpdateWrapper.class));
    }
}
