package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.dto.PromptPolicySaveRequest;
import org.yilena.luna.prompt.governance.entity.PromptPolicyEntity;

import java.util.List;
import java.util.Set;

public interface PromptPolicyService {
    PromptPolicyEntity getByPolicyId(String policyId);

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
