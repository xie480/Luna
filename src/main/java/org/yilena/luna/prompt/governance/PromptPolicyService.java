package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.dto.PromptPolicySaveRequest;
import org.yilena.luna.prompt.governance.entity.PromptPolicyEntity;
import org.yilena.luna.prompt.governance.entity.PromptPolicyVersionEntity;
import org.yilena.luna.prompt.governance.model.PromptPolicyDetailView;

import java.util.List;
import java.util.Set;

/**
 * 提示策略服务接口，负责维护提示策略包、策略版本和包含排除规则，
 * 用于控制不同场景下提示词集合的生效范围。
 */
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

    default List<PromptPolicyVersionEntity> listPolicyVersions(String policyId) {
        return List.of();
    }

    default void activatePolicyVersion(String policyId, Long versionId) {
        throw new UnsupportedOperationException("activatePolicyVersion is not implemented");
    }
}
