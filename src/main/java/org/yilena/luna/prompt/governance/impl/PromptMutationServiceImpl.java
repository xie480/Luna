package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptMutationService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptVersionService;
import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;
import org.yilena.luna.prompt.governance.model.EditPolicy;
import org.yilena.luna.prompt.governance.model.MatchScope;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.support.PromptKeyAliasSupport;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PromptMutationServiceImpl implements PromptMutationService {

    private final PromptItemMapper promptItemMapper;
    private final PromptItemVersionMapper promptItemVersionMapper;
    private final PromptVersionService promptVersionService;
    private final PromptRegistryService promptRegistryService;
    private final PromptCategoryService promptCategoryService;
    private static final Set<String> EXECUTION_ALLOWED_MODES = Set.of("ALWAYS", "AGENT_ONLY", "POLICY_ONLY", "MANUAL_ONLY", "DISABLED");
    private static final Set<String> CONTENT_ALLOWED_MODES = Set.of("ALWAYS", "KEYWORD_ONLY", "KEYWORD_AND_AGENT", "KEYWORD_OR_AGENT", "DISABLED");
    private static final Pattern TEMPLATE_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{[^}]+}");
    private static final Pattern PROMPT_KEY_MAIN_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]*\\.[a-z0-9][a-z0-9_-]*\\.[a-z0-9][a-z0-9_-]*_[a-z0-9][a-z0-9._-]*$");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptItemRecord create(PromptUpsertRequest request) {
        validateCreateRequest(request);
        if (promptRegistryService.existsByKey(request.getKey())) {
            throw new IllegalArgumentException("prompt key already exists");
        }
        String targetCategory = resolveRequestCategory(request);
        boolean enabled = resolveItemEnabled(request.getEnabled(), request.getStatus(), true);
        PromptItemEntity item = PromptItemEntity.builder()
                .category(targetCategory)
                .categoryKey(targetCategory)
                .subCategory(request.getSubCategory())
                .promptKey(request.getKey())
                .promptName(safe(request.getPromptName(), request.getKey()))
                .runtimeSlot(request.getRuntimeSlot())
                .hasTemplateVariables(false)
                .keywordMatchEnabled(resolveContentKeywordEnabled(request))
                .assemblyMode(safe(request.getAssemblyMode(), "KEYWORD_ONLY"))
                .enabled(enabled)
                .priority(request.getPriority() == null ? 80 : request.getPriority())
                .status(normalizeItemStatus(request.getStatus(), enabled))
                .isBuiltin(false)
                .description(safe(request.getDescription()))
                .build();
        promptItemMapper.insert(item);
        PromptItemVersionEntity version = buildVersion(item.getId(), request, item, null);
        version.setIsActive(true);
        version.setStatus(normalizeVersionStatus(version.getStatus(), "active"));
        promptItemVersionMapper.insert(version);
        promptItemMapper.update(null,
                new LambdaUpdateWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getId, item.getId())
                        .set(PromptItemEntity::getCurrentVersionId, version.getId()));
        return promptRegistryService.getByKey(request.getKey()).orElseThrow();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptItemRecord update(PromptUpsertRequest request) {
        if (request == null || request.getKey() == null || request.getKey().isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        validatePromptKeyForWrite(request.getKey());
        PromptItemEntity item = promptItemMapper.selectOne(
                new LambdaQueryWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getPromptKey, request.getKey())
                        .last("limit 1")
        );
        if (item == null) {
            throw new IllegalArgumentException("prompt not found");
        }
        PromptItemVersionEntity current = item.getCurrentVersionId() == null ? null : promptItemVersionMapper.selectById(item.getCurrentVersionId());
        if (current != null && current.getEditPolicy() != null && !readPolicy(current.getEditPolicy(), "update")) {
            throw new IllegalArgumentException("prompt update policy denied");
        }
        String currentCategory = resolveItemCategory(item);
        String requestedCategory = resolveRequestCategory(request);
        if (requestedCategory != null && !requestedCategory.isBlank()) {
            ensureCategoryExists(requestedCategory);
        }
        String targetCategory = resolveTargetCategory(item, request);
        String targetSubCategory = resolveTargetSubCategory(item, request);
        validateKeyCategoryConsistency(request.getKey(), targetCategory, targetSubCategory);
        if (!isExecutionCategory(currentCategory) && isExecutionCategory(targetCategory)) {
            throw new IllegalArgumentException("content prompt cannot be migrated to execution category");
        }
        if (isExecutionCategory(currentCategory) && !isExecutionCategory(targetCategory)) {
            throw new IllegalArgumentException("execution prompt category cannot be migrated to content category");
        }
        boolean hasTemplateVariables = bool(item.getHasTemplateVariables(), false);
        boolean executionCategory = isExecutionCategory(targetCategory);
        boolean executionPrompt = hasTemplateVariables || executionCategory;
        if (hasTemplateVariables || executionCategory) {
            validateExecutionPromptUpdate(current, request);
        } else {
            validateContentPromptUpdate(request);
        }
        Boolean nextKeywordEnabled = executionCategory
                ? Boolean.FALSE
                : (request.getKeywordMatchEnabled() == null ? null : request.getKeywordMatchEnabled());
        String requestedAssemblyMode = request.getAssemblyMode();
        String nextAssemblyMode = executionCategory
                ? normalizeExecutionAssemblyMode(requestedAssemblyMode, item.getAssemblyMode())
                : requestedAssemblyMode;
        Boolean requestedEnabled = resolveItemEnabledForUpdate(request.getEnabled(), request.getStatus());
        validateExecutionEnabledStatusMutation(executionPrompt, requestedEnabled, request.getStatus());
        Boolean effectiveEnabled = executionPrompt ? Boolean.TRUE : requestedEnabled;
        boolean finalEnabled = executionPrompt
                ? true
                : (effectiveEnabled == null ? bool(item.getEnabled(), true) : effectiveEnabled);
        boolean updateEnabledField = executionPrompt || effectiveEnabled != null;
        boolean updateStatusField = executionPrompt || request.getStatus() != null || effectiveEnabled != null;
        String effectiveStatus = executionPrompt ? "enabled" : normalizeItemStatus(request.getStatus(), finalEnabled);
        promptItemMapper.update(null,
                new LambdaUpdateWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getId, item.getId())
                        .set(requestedCategory != null && !requestedCategory.isBlank(),
                                PromptItemEntity::getCategory, requestedCategory)
                        .set(requestedCategory != null && !requestedCategory.isBlank(),
                                PromptItemEntity::getCategoryKey, requestedCategory)
                        .set(request.getSubCategory() != null, PromptItemEntity::getSubCategory, request.getSubCategory())
                        .set(request.getPromptName() != null, PromptItemEntity::getPromptName, request.getPromptName())
                        .set(request.getDescription() != null, PromptItemEntity::getDescription, request.getDescription())
                        .set(request.getRuntimeSlot() != null, PromptItemEntity::getRuntimeSlot, request.getRuntimeSlot())
                        .set(nextKeywordEnabled != null, PromptItemEntity::getKeywordMatchEnabled, nextKeywordEnabled)
                        .set(nextAssemblyMode != null, PromptItemEntity::getAssemblyMode, nextAssemblyMode)
                        .set(updateEnabledField, PromptItemEntity::getEnabled, executionPrompt ? true : effectiveEnabled)
                        .set(request.getPriority() != null, PromptItemEntity::getPriority, request.getPriority())
                        .set(updateStatusField, PromptItemEntity::getStatus, effectiveStatus));

        PromptItemVersionEntity version = buildVersion(item.getId(), request, item, current);
        boolean previewOnly = executionPrompt && Boolean.TRUE.equals(request.getPreviewOnly());
        version.setStatus(previewOnly ? "draft" : normalizeVersionStatus(request.getStatus(), "active"));
        version.setIsActive(!previewOnly);
        promptItemVersionMapper.insert(version);
        if (!previewOnly) {
            promptVersionService.activateVersion(version.getId());
        }
        return promptRegistryService.getByKey(request.getKey()).orElseThrow();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        PromptItemEntity item = promptItemMapper.selectOne(
                new LambdaQueryWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getPromptKey, key)
                        .last("limit 1")
        );
        if (item == null) {
            return;
        }
        PromptItemVersionEntity current = item.getCurrentVersionId() == null ? null : promptItemVersionMapper.selectById(item.getCurrentVersionId());
        if (isExecutionCategory(resolveItemCategory(item))) {
            throw new IllegalArgumentException("execution prompt category cannot be deleted");
        }
        if (bool(item.getHasTemplateVariables(), false)) {
            throw new IllegalArgumentException("execution prompt cannot be deleted");
        }
        if (!isDeleteAllowed(current)) {
            throw new IllegalArgumentException("prompt delete policy denied");
        }
        promptItemMapper.update(null,
                new LambdaUpdateWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getId, item.getId())
                        .set(PromptItemEntity::getEnabled, false)
                        .set(PromptItemEntity::getStatus, "disabled"));
    }

    private PromptItemVersionEntity buildVersion(Long itemId,
                                                 PromptUpsertRequest request,
                                                 PromptItemEntity item,
                                                 PromptItemVersionEntity currentVersion) {
        boolean executionCategory = isExecutionCategory(resolveItemCategory(item));
        boolean hasTemplateVariables = bool(item == null ? null : item.getHasTemplateVariables(), false);
        boolean contentCreate = currentVersion == null && !executionCategory && !hasTemplateVariables;
        List<String> templateVariables = contentCreate
                ? List.of()
                : mergeListField(request == null ? null : request.getTemplateVariables(),
                currentVersion == null ? null : currentVersion.getTemplateVariables());
        List<String> matchKeywords = mergeListField(
                request == null ? null : request.getMatchKeywords(),
                currentVersion == null ? null : currentVersion.getMatchKeywords());
        Map<String, Object> matchScope = mergeScopeField(
                request == null ? null : request.getMatchScope(),
                currentVersion == null ? null : currentVersion.getMatchScope());
        return PromptItemVersionEntity.builder()
                .promptItemId(itemId)
                .versionNo(safe(request.getVersion(), nextVersion(itemId)))
                .versionLabel(safe(request.getVersionLabel(), safe(request.getVersion())))
                .promptValue(safe(request.getValue()))
                .templateVariables(templateVariables)
                .matchKeywords(matchKeywords)
                .matchScope(matchScope)
                .editPolicy(resolveEditPolicy(request, currentVersion, hasTemplateVariables, executionCategory).toMap())
                .changeNote(safe(request.getChangeNote()))
                .status(normalizeVersionStatus(request == null ? null : request.getStatus(), "active"))
                .build();
    }

    private EditPolicy resolveEditPolicy(PromptUpsertRequest request,
                                         PromptItemVersionEntity currentVersion,
                                         boolean hasTemplateVariables,
                                         boolean executionCategory) {
        if (request != null && request.getEditPolicy() != null) {
            return EditPolicy.fromMap(request.getEditPolicy());
        }
        if (currentVersion != null && currentVersion.getEditPolicy() != null && !currentVersion.getEditPolicy().isEmpty()) {
            return EditPolicy.fromMap(currentVersion.getEditPolicy());
        }
        return defaultEditPolicy(hasTemplateVariables, executionCategory);
    }

    private EditPolicy defaultEditPolicy(boolean hasTemplateVariables, boolean executionCategory) {
        boolean contentPrompt = !hasTemplateVariables && !executionCategory;
        return contentPrompt ? EditPolicy.contentDefault() : EditPolicy.executionDefault();
    }

    private void validateCreateRequest(PromptUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.getKey() == null || request.getKey().isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        validatePromptKeyForWrite(request.getKey());
        if (request.getCategory() == null || request.getCategory().isBlank()) {
            if (request.getCategoryKey() == null || request.getCategoryKey().isBlank()) {
                throw new IllegalArgumentException("category is required");
            }
        }
        String categoryKey = resolveRequestCategory(request);
        validateKeyCategoryConsistency(request.getKey(), categoryKey, request.getSubCategory());
        Optional<PromptCategoryEntity> category = promptCategoryService.findByKey(categoryKey);
        if (category.isEmpty()) {
            throw new IllegalArgumentException("category must exist in prompt_category");
        }
        if (Boolean.TRUE.equals(category.get().getIsExecutionCategory()) || isExecutionCategory(categoryKey)) {
            throw new IllegalArgumentException("create only supports content prompt category");
        }
        if (bool(request.getHasTemplateVariables(), false)) {
            throw new IllegalArgumentException("create only supports content prompt");
        }
        if (hasTemplateVariablesInRequest(request.getTemplateVariables())) {
            throw new IllegalArgumentException("content prompt create cannot carry templateVariables");
        }
        if (containsTemplatePlaceholder(request.getValue())) {
            throw new IllegalArgumentException("content prompt create value cannot carry template placeholder");
        }
        String mode = request.getAssemblyMode();
        if (mode != null && !mode.isBlank()) {
            String normalizedMode = mode.trim().toUpperCase();
            if (!CONTENT_ALLOWED_MODES.contains(normalizedMode)) {
                throw new IllegalArgumentException("content prompt assembly_mode is not allowed");
            }
        }
        if (request.getKeywordMatchEnabled() != null && !request.getKeywordMatchEnabled()) {
            // allowed
        }
    }

    private void validateExecutionPromptUpdate(PromptItemVersionEntity current, PromptUpsertRequest request) {
        List<String> oldVars = current == null || current.getTemplateVariables() == null ? List.of() : current.getTemplateVariables();
        List<String> newVars = request.getTemplateVariables() == null ? oldVars : request.getTemplateVariables();
        if (!Objects.equals(oldVars, newVars)) {
            throw new IllegalArgumentException("execution prompt templateVariables cannot add/delete");
        }
        if (request.getKeywordMatchEnabled() != null && request.getKeywordMatchEnabled()) {
            throw new IllegalArgumentException("execution prompt cannot enable keyword matching");
        }
        if (request.getAssemblyMode() != null && !EXECUTION_ALLOWED_MODES.contains(request.getAssemblyMode().trim().toUpperCase())) {
            throw new IllegalArgumentException("execution prompt assembly_mode is not allowed");
        }
    }

    private void validateContentPromptUpdate(PromptUpsertRequest request) {
        if (request == null) {
            return;
        }
        if (bool(request.getHasTemplateVariables(), false)) {
            throw new IllegalArgumentException("content prompt update cannot carry hasTemplateVariables");
        }
        if (hasTemplateVariablesInRequest(request.getTemplateVariables())) {
            throw new IllegalArgumentException("content prompt update cannot carry templateVariables");
        }
        if (containsTemplatePlaceholder(request.getValue())) {
            throw new IllegalArgumentException("content prompt update value cannot carry template placeholder");
        }
        if (request.getAssemblyMode() == null || request.getAssemblyMode().isBlank()) {
            return;
        }
        String normalizedMode = request.getAssemblyMode().trim().toUpperCase();
        if (!CONTENT_ALLOWED_MODES.contains(normalizedMode)) {
            throw new IllegalArgumentException("content prompt assembly_mode is not allowed");
        }
    }

    private boolean readPolicy(Map<String, Object> policy, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        EditPolicy editPolicy = EditPolicy.fromMap(policy);
        if ("create".equalsIgnoreCase(key)) {
            return Boolean.TRUE.equals(editPolicy.getCreate());
        }
        if ("update".equalsIgnoreCase(key)) {
            return !Boolean.FALSE.equals(editPolicy.getUpdate());
        }
        if ("delete".equalsIgnoreCase(key)) {
            return Boolean.TRUE.equals(editPolicy.getDelete());
        }
        return false;
    }

    private boolean isDeleteAllowed(PromptItemVersionEntity currentVersion) {
        if (currentVersion == null || currentVersion.getEditPolicy() == null || currentVersion.getEditPolicy().isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(EditPolicy.fromMap(currentVersion.getEditPolicy()).getDelete());
    }

    private List<String> mergeListField(List<String> requestField, List<String> currentField) {
        if (requestField != null) {
            return requestField;
        }
        if (currentField != null) {
            return currentField;
        }
        return List.of();
    }

    private Map<String, Object> mergeScopeField(Map<String, Object> requestField, Map<String, Object> currentField) {
        if (requestField != null) {
            return MatchScope.fromMap(requestField).toMap();
        }
        if (currentField != null && !currentField.isEmpty()) {
            return MatchScope.fromMap(currentField).toMap();
        }
        return MatchScope.empty().toMap();
    }

    private String nextVersion(Long itemId) {
        List<PromptItemVersionEntity> versions = promptItemVersionMapper.selectList(
                new LambdaQueryWrapper<PromptItemVersionEntity>()
                        .eq(PromptItemVersionEntity::getPromptItemId, itemId)
                        .orderByDesc(PromptItemVersionEntity::getCreatedAt)
                        .last("limit 1")
        );
        if (versions.isEmpty() || versions.get(0).getVersionNo() == null || versions.get(0).getVersionNo().isBlank()) {
            return "1.0.0";
        }
        String[] parts = versions.get(0).getVersionNo().split("\\.");
        if (parts.length != 3) {
            return "1.0.0";
        }
        try {
            int patch = Integer.parseInt(parts[2]) + 1;
            return parts[0] + "." + parts[1] + "." + patch;
        } catch (Exception ignore) {
            return "1.0.0";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private boolean bool(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private boolean hasTemplateVariablesInRequest(List<String> templateVariables) {
        return templateVariables != null && !templateVariables.isEmpty();
    }

    private boolean containsTemplatePlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return TEMPLATE_PLACEHOLDER_PATTERN.matcher(value).find();
    }

    private boolean isExecutionCategory(String category) {
        return promptCategoryService.isExecutionCategory(category);
    }

    private void ensureCategoryExists(String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) {
            return;
        }
        Optional<PromptCategoryEntity> category = promptCategoryService.findByKey(categoryKey);
        if (category.isEmpty()) {
            throw new IllegalArgumentException("category must exist in prompt_category");
        }
    }

    private void validatePromptKeyForWrite(String key) {
        String normalized = safe(key).trim();
        if (!PROMPT_KEY_MAIN_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("prompt key must match {category}.{subCategory}.{name}_{versionTag}");
        }
        String canonical = PromptKeyAliasSupport.canonicalKeyOf(normalized);
        if (!canonical.equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("prompt key alias is not allowed for write, use canonical key");
        }
    }

    private void validateKeyCategoryConsistency(String key, String category, String subCategory) {
        PromptKeyPrefix prefix = parsePromptKeyPrefix(key);
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category must be consistent with prompt key");
        }
        if (subCategory == null || subCategory.isBlank()) {
            throw new IllegalArgumentException("subCategory must be consistent with prompt key");
        }
        String normalizedCategory = category.trim();
        String normalizedSubCategory = subCategory.trim();
        if (!prefix.category.equalsIgnoreCase(normalizedCategory)) {
            throw new IllegalArgumentException("prompt key category prefix must match category");
        }
        if (!prefix.subCategory.equalsIgnoreCase(normalizedSubCategory)) {
            throw new IllegalArgumentException("prompt key subCategory prefix must match subCategory");
        }
    }

    private void validateExecutionEnabledStatusMutation(boolean executionPrompt, Boolean requestedEnabled, String requestedStatus) {
        if (!executionPrompt) {
            return;
        }
        if (requestedEnabled != null && !requestedEnabled) {
            throw new IllegalArgumentException("execution prompt cannot be disabled");
        }
        if (requestedStatus == null || requestedStatus.isBlank()) {
            return;
        }
        String normalized = requestedStatus.trim().toLowerCase();
        if ("disabled".equals(normalized)) {
            throw new IllegalArgumentException("execution prompt status cannot be set to disabled");
        }
    }

    private String resolveTargetCategory(PromptItemEntity item, PromptUpsertRequest request) {
        String requestCategory = resolveRequestCategory(request);
        if (requestCategory != null && !requestCategory.isBlank()) {
            return requestCategory;
        }
        return resolveItemCategory(item);
    }

    private String resolveTargetSubCategory(PromptItemEntity item, PromptUpsertRequest request) {
        if (request != null && request.getSubCategory() != null && !request.getSubCategory().isBlank()) {
            return request.getSubCategory();
        }
        return item == null ? "" : safe(item.getSubCategory());
    }

    private Boolean resolveContentKeywordEnabled(PromptUpsertRequest request) {
        if (request == null) {
            return true;
        }
        boolean categoryAllowed = promptCategoryService.isKeywordMatchAllowed(resolveRequestCategory(request));
        if (!categoryAllowed) {
            return false;
        }
        return bool(request.getKeywordMatchEnabled(), true);
    }

    private String resolveRequestCategory(PromptUpsertRequest request) {
        if (request == null) {
            return "";
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            return request.getCategory();
        }
        return request.getCategoryKey() == null ? "" : request.getCategoryKey();
    }

    private String resolveItemCategory(PromptItemEntity item) {
        if (item == null) {
            return "";
        }
        if (item.getCategory() != null && !item.getCategory().isBlank()) {
            return item.getCategory();
        }
        return safe(item.getCategoryKey());
    }

    private boolean resolveItemEnabled(Boolean enabled, String status, boolean fallback) {
        if (enabled != null) {
            return enabled;
        }
        if (status == null || status.isBlank()) {
            return fallback;
        }
        String normalized = status.trim().toLowerCase();
        if ("enabled".equals(normalized)) {
            return true;
        }
        if ("disabled".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    private Boolean resolveItemEnabledForUpdate(Boolean enabled, String status) {
        if (enabled != null) {
            return enabled;
        }
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toLowerCase();
        if ("enabled".equals(normalized)) {
            return true;
        }
        if ("disabled".equals(normalized)) {
            return false;
        }
        return null;
    }

    private String normalizeItemStatus(String status, boolean enabled) {
        if (status == null || status.isBlank()) {
            return enabled ? "enabled" : "disabled";
        }
        String normalized = status.trim().toLowerCase();
        if ("enabled".equals(normalized)) {
            return "enabled";
        }
        if ("disabled".equals(normalized)) {
            return "disabled";
        }
        return enabled ? "enabled" : "disabled";
    }

    private String normalizeVersionStatus(String status, String fallback) {
        if (status == null || status.isBlank()) {
            return fallback;
        }
        String normalized = status.trim().toLowerCase();
        if ("draft".equals(normalized) || "active".equals(normalized) || "archived".equals(normalized)) {
            return normalized;
        }
        return fallback;
    }

    private String normalizeExecutionAssemblyMode(String requested, String existing) {
        String target = requested == null || requested.isBlank() ? safe(existing, "AGENT_ONLY") : requested.trim().toUpperCase();
        if (!EXECUTION_ALLOWED_MODES.contains(target)) {
            throw new IllegalArgumentException("execution prompt assembly_mode is not allowed");
        }
        return target;
    }

    private PromptKeyPrefix parsePromptKeyPrefix(String key) {
        String normalized = safe(key).trim();
        String[] parts = normalized.split("\\.", 3);
        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("prompt key must match {category}.{subCategory}.{name}_{versionTag}");
        }
        return new PromptKeyPrefix(parts[0], parts[1]);
    }

    private static final class PromptKeyPrefix {
        private final String category;
        private final String subCategory;

        private PromptKeyPrefix(String category, String subCategory) {
            this.category = category;
            this.subCategory = subCategory;
        }
    }
}
