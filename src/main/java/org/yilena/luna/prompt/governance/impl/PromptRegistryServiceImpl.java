package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PromptRegistryServiceImpl implements PromptRegistryService {

    private final PromptItemMapper promptItemMapper;
    private final PromptItemVersionMapper promptItemVersionMapper;
    private final PromptCategoryService promptCategoryService;
    @Value("${prompt.governance.builtin-fallback-enabled:false}")
    private boolean builtinFallbackEnabled;

    private final Map<String, PromptItemRecord> builtins = BuiltinPromptCatalog.all();

    @Override
    public Optional<PromptItemRecord> getByKey(String key) {
        return loadByKey(key, false);
    }

    @Override
    public Optional<PromptItemRecord> getByKeyIncludingDisabled(String key) {
        return loadByKey(key, true);
    }

    @Override
    public Optional<PromptItemRecord> getById(Long id) {
        return loadById(id, false);
    }

    @Override
    public Optional<PromptItemRecord> getByIdIncludingDisabled(Long id) {
        return loadById(id, true);
    }

    private Optional<PromptItemRecord> loadByKey(String key, boolean includeDisabled) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String normalized = key.trim();
        try {
            PromptItemEntity item = promptItemMapper.selectOne(
                    new LambdaQueryWrapper<PromptItemEntity>()
                            .eq(PromptItemEntity::getPromptKey, normalized)
                            .last("limit 1")
            );
            PromptItemRecord exact = toRecordIfVisible(item, includeDisabled);
            if (exact != null) {
                return Optional.of(exact);
            }
            boolean existsButInactive = item != null && !includeDisabled;
            if (existsButInactive) {
                return Optional.empty();
            }
            for (PromptItemRecord candidate : listAll(includeDisabled)) {
                if (isKeyAliasMatch(normalized, candidate.getKey())) {
                    return Optional.of(candidate);
                }
            }
            if (builtinFallbackEnabled) {
                PromptItemRecord directBuiltin = builtins.get(normalized);
                if (directBuiltin != null) {
                    return Optional.of(directBuiltin);
                }
                for (PromptItemRecord builtin : builtins.values()) {
                    if (isKeyAliasMatch(normalized, builtin.getKey())) {
                        return Optional.of(builtin);
                    }
                }
            }
            return Optional.empty();
        } catch (Exception ignore) {
            return builtinFallbackEnabled ? Optional.ofNullable(builtins.get(normalized)) : Optional.empty();
        }
    }

    private Optional<PromptItemRecord> loadById(Long id, boolean includeDisabled) {
        if (id == null || id <= 0) {
            return Optional.empty();
        }
        try {
            PromptItemEntity item = promptItemMapper.selectById(id);
            if (item == null) {
                return Optional.empty();
            }
            PromptItemRecord record = toRecordIfVisible(item, includeDisabled);
            if (record == null) {
                return Optional.empty();
            }
            return Optional.of(record);
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    @Override
    public boolean existsByKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim();
        if (getByKeyIncludingDisabled(normalized).isPresent()) {
            return true;
        }
        return builtinFallbackEnabled && builtins.containsKey(normalized);
    }

    @Override
    public List<PromptItemRecord> listAllActive() {
        return listAll(false);
    }

    private List<PromptItemRecord> listAll(boolean includeDisabled) {
        Map<String, PromptItemRecord> merged = new LinkedHashMap<>();
        if (builtinFallbackEnabled) {
            merged.putAll(builtins);
        }
        try {
            List<PromptItemEntity> items = promptItemMapper.selectList(new LambdaQueryWrapper<>());
            Map<Long, PromptItemVersionEntity> versionById = loadVersionMap(items);
            for (PromptItemEntity item : items) {
                if (!includeDisabled && !isItemActive(item)) {
                    merged.remove(item.getPromptKey());
                    continue;
                }
                PromptItemVersionEntity version = versionById.get(item.getCurrentVersionId());
                if (!isCurrentVersionActive(version)) {
                    if (!includeDisabled) {
                        merged.remove(item.getPromptKey());
                    }
                    continue;
                }
                PromptItemRecord record = merge(item, version);
                if (record != null) {
                    merged.put(record.getKey(), record);
                }
            }
        } catch (Exception ignore) {
            // ignore and return current merged view
        }
        return merged.values().stream()
                .filter(item -> includeDisabled || item.isEnabled())
                .sorted(Comparator.comparingInt((PromptItemRecord item) -> item.getPriority() == null ? 0 : item.getPriority()).reversed())
                .toList();
    }

    @Override
    public Map<String, String> listKeyValueByCategory(String category) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PromptItemRecord item : listByCategory(category, null)) {
            out.put(item.getKey(), item.getValue());
        }
        return out;
    }

    @Override
    public List<PromptItemRecord> listByCategory(String category, String subCategory) {
        return listAllActive().stream()
                .filter(item -> category == null || category.isBlank() || category.equalsIgnoreCase(item.getCategory()))
                .filter(item -> subCategory == null || subCategory.isBlank() || subCategory.equalsIgnoreCase(item.getSubCategory()))
                .toList();
    }

    @Override
    public List<String> listCategories() {
        try {
            List<String> categories = promptCategoryService.listEnabledOrdered().stream()
                    .map(PromptCategoryEntity::getCategoryKey)
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .toList();
            if (!categories.isEmpty()) {
                return categories;
            }
        } catch (Exception ignore) {
            // fallback to item-driven categories
        }
        if (builtinFallbackEnabled) {
            List<String> builtinCategories = builtins.values().stream()
                    .map(PromptItemRecord::getCategory)
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
            if (!builtinCategories.isEmpty()) {
                return builtinCategories;
            }
        }
        return listAllActive().stream()
                .map(PromptItemRecord::getCategory)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public String resolvePromptValue(String key, String fallbackValue) {
        Optional<PromptItemRecord> record = getByKey(key);
        if (record.isPresent() && record.get().getValue() != null && !record.get().getValue().isBlank()) {
            return record.get().getValue();
        }
        return fallbackValue == null ? "" : fallbackValue;
    }

    private Map<Long, PromptItemVersionEntity> loadVersionMap(List<PromptItemEntity> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Set<Long> versionIds = items.stream()
                .map(PromptItemEntity::getCurrentVersionId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        List<PromptItemVersionEntity> versions = promptItemVersionMapper.selectBatchIds(new ArrayList<>(versionIds));
        return versions.stream().collect(Collectors.toMap(PromptItemVersionEntity::getId, v -> v, (a, b) -> a));
    }

    private PromptItemVersionEntity loadCurrentVersion(Long versionId) {
        if (versionId == null || versionId <= 0) {
            return null;
        }
        return promptItemVersionMapper.selectById(versionId);
    }

    private PromptItemRecord merge(PromptItemEntity item, PromptItemVersionEntity version) {
        if (item == null) {
            return null;
        }
        PromptItemRecord fallback = builtinFallbackEnabled ? builtins.get(item.getPromptKey()) : null;
        boolean enabled = bool(item.getEnabled(), true);
        return PromptItemRecord.builder()
                .itemId(item.getId())
                .versionId(version == null ? null : version.getId())
                .key(item.getPromptKey())
                .name(safe(item.getPromptName(), fallback == null ? item.getPromptKey() : fallback.getName()))
                .value(version == null ? (fallback == null ? "" : fallback.getValue()) : safe(version.getPromptValue()))
                .category(firstNonBlank(item.getCategory(), item.getCategoryKey(), fallback == null ? "" : fallback.getCategory()))
                .subCategory(safe(item.getSubCategory(), fallback == null ? "" : fallback.getSubCategory()))
                .description(safe(item.getDescription(), fallback == null ? "" : fallback.getDescription()))
                .runtimeSlot(safe(item.getRuntimeSlot(), fallback == null ? "" : fallback.getRuntimeSlot()))
                .hasTemplateVariables(bool(item.getHasTemplateVariables(), fallback != null && fallback.isHasTemplateVariables()))
                .templateVariables(version == null || version.getTemplateVariables() == null ? List.of() : version.getTemplateVariables())
                .keywordMatchEnabled(bool(item.getKeywordMatchEnabled(), fallback != null && fallback.isKeywordMatchEnabled()))
                .matchKeywords(version == null || version.getMatchKeywords() == null ? List.of() : version.getMatchKeywords())
                .assemblyMode(safe(item.getAssemblyMode(), fallback == null ? "ALWAYS" : fallback.getAssemblyMode()))
                .matchScope(version == null || version.getMatchScope() == null ? Map.of() : version.getMatchScope())
                .editPolicy(version == null || version.getEditPolicy() == null ? Map.of() : version.getEditPolicy())
                .enabled(enabled)
                .priority(item.getPriority() == null ? (fallback == null ? 80 : fallback.getPriority()) : item.getPriority())
                .status(safe(item.getStatus(), enabled ? "enabled" : "disabled"))
                .version(version == null ? (fallback == null ? "1.0.0" : fallback.getVersion()) : safe(version.getVersionNo()))
                .versionLabel(version == null ? (fallback == null ? "" : fallback.getVersionLabel()) : safe(version.getVersionLabel()))
                .changeNote(version == null ? (fallback == null ? "" : fallback.getChangeNote()) : safe(version.getChangeNote()))
                .build();
    }

    private PromptItemRecord toRecordIfVisible(PromptItemEntity item, boolean includeDisabled) {
        if (item == null) {
            return null;
        }
        if (!includeDisabled && !isItemActive(item)) {
            return null;
        }
        PromptItemVersionEntity version = loadCurrentVersion(item.getCurrentVersionId());
        if (!isCurrentVersionActive(version)) {
            return null;
        }
        return merge(item, version);
    }

    private boolean isItemActive(PromptItemEntity item) {
        if (item == null) {
            return false;
        }
        if (!bool(item.getEnabled(), false)) {
            return false;
        }
        String status = safe(item.getStatus()).trim().toLowerCase();
        return status.isBlank()
                || "enabled".equals(status)
                || "active".equals(status)
                || "true".equals(status);
    }

    private boolean isKeyAliasMatch(String requested, String stored) {
        if (requested == null || requested.isBlank() || stored == null || stored.isBlank()) {
            return false;
        }
        if (stored.equalsIgnoreCase(requested)) {
            return true;
        }
        String requestedFull = normalizeKey(requested, false);
        String requestedSlim = normalizeKey(requested, true);
        String storedFull = normalizeKey(stored, false);
        String storedSlim = normalizeKey(stored, true);
        return (!requestedFull.isBlank() && requestedFull.equals(storedFull))
                || (!requestedSlim.isBlank() && requestedSlim.equals(storedFull))
                || (!storedSlim.isBlank() && storedSlim.equals(requestedFull))
                || (!requestedSlim.isBlank() && requestedSlim.equals(storedSlim));
    }

    private String normalizeKey(String key, boolean removeCategoryPrefix) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String normalized = key.trim().toLowerCase();
        if (removeCategoryPrefix && normalized.contains(".")) {
            String[] parts = normalized.split("\\.");
            if (parts.length > 1) {
                normalized = String.join("", java.util.Arrays.copyOfRange(parts, 1, parts.length));
            }
        }
        return normalized.replaceAll("[^a-z0-9]", "");
    }

    private boolean isCurrentVersionActive(PromptItemVersionEntity version) {
        return version != null
                && bool(version.getIsActive(), false)
                && "active".equalsIgnoreCase(safe(version.getStatus()));
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safe(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback == null ? "" : fallback;
    }

    private boolean bool(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
