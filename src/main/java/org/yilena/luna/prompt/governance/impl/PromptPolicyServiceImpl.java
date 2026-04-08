package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.dto.PromptPolicySaveRequest;
import org.yilena.luna.prompt.governance.entity.PromptPolicyEntity;
import org.yilena.luna.prompt.governance.entity.PromptPolicyVersionEntity;
import org.yilena.luna.prompt.governance.mapper.PromptPolicyMapper;
import org.yilena.luna.prompt.governance.mapper.PromptPolicyVersionMapper;
import org.yilena.luna.prompt.governance.model.PromptPolicyDetailView;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PromptPolicyServiceImpl implements PromptPolicyService {

    private final PromptPolicyMapper promptPolicyMapper;
    private final PromptPolicyVersionMapper promptPolicyVersionMapper;

    @Override
    public PromptPolicyEntity getByPolicyId(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return null;
        }
        try {
            return promptPolicyMapper.selectOne(
                    new LambdaQueryWrapper<PromptPolicyEntity>()
                            .eq(PromptPolicyEntity::getPolicyKey, policyId.trim())
                            .last("limit 1")
            );
        } catch (Exception ignore) {
            return null;
        }
    }

    @Override
    public PromptPolicyDetailView getPolicyDetail(String policyId) {
        PromptPolicyEntity policy = getByPolicyId(policyId);
        if (policy == null) {
            return null;
        }
        PromptPolicyVersionEntity current = policy.getCurrentVersionId() == null
                ? null
                : promptPolicyVersionMapper.selectById(policy.getCurrentVersionId());
        return PromptPolicyDetailView.builder()
                .id(policy.getId())
                .policyId(policy.getPolicyKey())
                .policyName(policy.getPolicyName())
                .description(policy.getDescription())
                .enabled(Boolean.TRUE.equals(policy.getEnabled()))
                .currentVersionId(policy.getCurrentVersionId())
                .currentVersionNo(current == null ? "" : blankToDefault(current.getVersionNo(), ""))
                .includePromptKeys(current == null || current.getIncludePromptKeys() == null ? List.of() : current.getIncludePromptKeys())
                .excludePromptKeys(current == null || current.getExcludePromptKeys() == null ? List.of() : current.getExcludePromptKeys())
                .build();
    }

    @Override
    public Set<String> resolveIncludedPromptKeys(String policyId) {
        PromptPolicyVersionEntity current = findCurrent(policyId);
        if (current == null || current.getIncludePromptKeys() == null) {
            return Set.of();
        }
        return current.getIncludePromptKeys().stream()
                .filter(key -> key != null && !key.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public Set<String> resolveExcludedPromptKeys(String policyId) {
        PromptPolicyVersionEntity current = findCurrent(policyId);
        if (current == null || current.getExcludePromptKeys() == null) {
            return Set.of();
        }
        return current.getExcludePromptKeys().stream()
                .filter(key -> key != null && !key.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public List<String> listPolicyIds() {
        try {
            return promptPolicyMapper.selectList(
                            new LambdaQueryWrapper<PromptPolicyEntity>().eq(PromptPolicyEntity::getEnabled, true))
                    .stream()
                    .map(PromptPolicyEntity::getPolicyKey)
                    .filter(key -> key != null && !key.isBlank())
                    .toList();
        } catch (Exception ignore) {
            return List.of();
        }
    }

    @Override
    public List<PromptPolicyEntity> listPolicies() {
        try {
            return promptPolicyMapper.selectList(
                    new LambdaQueryWrapper<PromptPolicyEntity>()
                            .orderByDesc(PromptPolicyEntity::getUpdatedAt)
            );
        } catch (Exception ignore) {
            return List.of();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptPolicyEntity savePolicy(PromptPolicySaveRequest request) {
        if (request == null || request.getPolicyId() == null || request.getPolicyId().isBlank()) {
            throw new IllegalArgumentException("policyId is required");
        }
        String policyKey = request.getPolicyId().trim();
        PromptPolicyEntity policy = promptPolicyMapper.selectOne(
                new LambdaQueryWrapper<PromptPolicyEntity>()
                        .eq(PromptPolicyEntity::getPolicyKey, policyKey)
                        .last("limit 1")
        );
        if (policy == null) {
            policy = PromptPolicyEntity.builder()
                    .policyKey(policyKey)
                    .policyName(blankToDefault(request.getPolicyName(), policyKey))
                    .description(blankToDefault(request.getDescription(), ""))
                    .enabled(request.getEnabled() == null || request.getEnabled())
                    .build();
            promptPolicyMapper.insert(policy);
        } else {
            promptPolicyMapper.update(null, new LambdaUpdateWrapper<PromptPolicyEntity>()
                    .eq(PromptPolicyEntity::getId, policy.getId())
                    .set(request.getPolicyName() != null, PromptPolicyEntity::getPolicyName, request.getPolicyName())
                    .set(request.getDescription() != null, PromptPolicyEntity::getDescription, request.getDescription())
                    .set(request.getEnabled() != null, PromptPolicyEntity::getEnabled, request.getEnabled()));
            policy = promptPolicyMapper.selectById(policy.getId());
        }
        PromptPolicyVersionEntity version = PromptPolicyVersionEntity.builder()
                .promptPolicyId(policy.getId())
                .versionNo(blankToDefault(request.getVersion(), nextVersion(policy.getId())))
                .includePromptKeys(request.getIncludePromptKeys() == null ? List.of() : request.getIncludePromptKeys())
                .excludePromptKeys(request.getExcludePromptKeys() == null ? List.of() : request.getExcludePromptKeys())
                .status("active")
                .changeNote(blankToDefault(request.getChangeNote(), ""))
                .isActive(true)
                .build();
        promptPolicyVersionMapper.update(null, new LambdaUpdateWrapper<PromptPolicyVersionEntity>()
                .eq(PromptPolicyVersionEntity::getPromptPolicyId, policy.getId())
                .set(PromptPolicyVersionEntity::getIsActive, false));
        promptPolicyVersionMapper.insert(version);
        promptPolicyMapper.update(null, new LambdaUpdateWrapper<PromptPolicyEntity>()
                .eq(PromptPolicyEntity::getId, policy.getId())
                .set(PromptPolicyEntity::getCurrentVersionId, version.getId())
                .set(PromptPolicyEntity::getEnabled, request.getEnabled() == null || request.getEnabled()));
        return promptPolicyMapper.selectById(policy.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePolicy(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("policyId is required");
        }
        PromptPolicyEntity policy = promptPolicyMapper.selectOne(
                new LambdaQueryWrapper<PromptPolicyEntity>()
                        .eq(PromptPolicyEntity::getPolicyKey, policyId.trim())
                        .last("limit 1")
        );
        if (policy == null) {
            return;
        }
        promptPolicyMapper.update(null, new LambdaUpdateWrapper<PromptPolicyEntity>()
                .eq(PromptPolicyEntity::getId, policy.getId())
                .set(PromptPolicyEntity::getEnabled, false)
                .set(PromptPolicyEntity::getDescription, mergeDeleteMarker(policy.getDescription())));
    }

    private PromptPolicyVersionEntity findCurrent(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return null;
        }
        try {
            PromptPolicyEntity policy = promptPolicyMapper.selectOne(
                    new LambdaQueryWrapper<PromptPolicyEntity>()
                            .eq(PromptPolicyEntity::getPolicyKey, policyId)
                            .eq(PromptPolicyEntity::getEnabled, true)
                            .last("limit 1")
            );
            if (policy == null || policy.getCurrentVersionId() == null) {
                return null;
            }
            return promptPolicyVersionMapper.selectById(policy.getCurrentVersionId());
        } catch (Exception ignore) {
            return null;
        }
    }

    private String nextVersion(Long policyId) {
        if (policyId == null || policyId <= 0) {
            return "1.0.0";
        }
        List<PromptPolicyVersionEntity> versions = promptPolicyVersionMapper.selectList(
                new LambdaQueryWrapper<PromptPolicyVersionEntity>()
                        .eq(PromptPolicyVersionEntity::getPromptPolicyId, policyId)
                        .orderByDesc(PromptPolicyVersionEntity::getCreatedAt)
                        .last("limit 1")
        );
        if (versions.isEmpty()) {
            return "1.0.0";
        }
        String version = versions.get(0).getVersionNo();
        if (version == null || version.isBlank()) {
            return "1.0.0";
        }
        String[] parts = version.split("\\.");
        if (parts.length != 3) {
            return "1.0.0";
        }
        try {
            return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
        } catch (Exception ignore) {
            return "1.0.0";
        }
    }

    private String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String mergeDeleteMarker(String description) {
        String base = description == null ? "" : description.trim();
        if (base.isBlank()) {
            return "[deleted]";
        }
        return base + " [deleted]";
    }
}
