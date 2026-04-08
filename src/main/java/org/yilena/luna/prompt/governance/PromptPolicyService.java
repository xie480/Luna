package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.dto.PromptPolicySaveRequest;
import org.yilena.luna.prompt.governance.entity.PromptPolicyEntity;
import org.yilena.luna.prompt.governance.model.PromptPolicyDetailView;

import java.util.List;
import java.util.Set;

public interface PromptPolicyService {
    PromptPolicyEntity getByPolicyId(String policyId);

    default PromptPolicyDetailView getPolicyDetail(String policyId) {
        PromptPolicyEntity entity = getByPolicyId(policyId);
        if (entity == null) {
            return null;
        }
        return PromptPolicyDetailView.builder()
                .id(entity.getId())
                .policyId(entity.getPolicyKey())
                .policyName(entity.getPolicyName())
                .description(entity.getDescription())
                .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                .currentVersionId(entity.getCurrentVersionId())
                .currentVersionNo("")
                .includePromptKeys(List.of())
                .excludePromptKeys(List.of())
                .build();
    }

    Set<String> resolveIncludedPromptKeys(String policyId);

    Set<String> resolveExcludedPromptKeys(String policyId);

    List<String> listPolicyIds();

    default List<PromptPolicyEntity> listPolicies() {
        return List.of();
    }

    default PromptPolicyEntity savePolicy(PromptPolicySaveRequest request) {
        throw new UnsupportedOperationException("savePolicy is not implemented");
    }

    default void deletePolicy(String policyId) {
        throw new UnsupportedOperationException("deletePolicy is not implemented");
    }
}
