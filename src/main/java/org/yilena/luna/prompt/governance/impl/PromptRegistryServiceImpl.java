package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
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

    private final Map<String, PromptItemRecord> builtins = BuiltinPromptCatalog.all();

    @Override
    public Optional<PromptItemRecord> getByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            PromptItemEntity item = promptItemMapper.selectOne(
                    new LambdaQueryWrapper<PromptItemEntity>()
                            .eq(PromptItemEntity::getPromptKey, key.trim())
                            .last("limit 1")
            );
            if (item == null) {
                return Optional.ofNullable(builtins.get(key));
            }
            PromptItemRecord merged = merge(item, loadCurrentVersion(item.getCurrentVersionId()));
            if (merged == null) {
                return Optional.ofNullable(builtins.get(key));
            }
            return Optional.of(merged);
        } catch (Exception ignore) {
            return Optional.ofNullable(builtins.get(key));
        }
    }

    @Override
    public List<PromptItemRecord> listAllActive() {
        Map<String, PromptItemRecord> merged = new LinkedHashMap<>(builtins);
        try {
            List<PromptItemEntity> items = promptItemMapper.selectList(
                    new LambdaQueryWrapper<PromptItemEntity>()
                            .eq(PromptItemEntity::getEnabled, true)
            );
            Map<Long, PromptItemVersionEntity> versionById = loadVersionMap(items);
            for (PromptItemEntity item : items) {
                PromptItemRecord record = merge(item, versionById.get(item.getCurrentVersionId()));
                if (record != null) {
                    merged.put(record.getKey(), record);
                }
            }
        } catch (Exception ignore) {
            // ignore and fallback to builtins only
        }
        return merged.values().stream()
                .filter(PromptItemRecord::isEnabled)
                .sorted(Comparator.comparingInt((PromptItemRecord item) -> item.getPriority() == null ? 0 : item.getPriority()).reversed())
                .toList();
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
        PromptItemRecord fallback = builtins.get(item.getPromptKey());
        return PromptItemRecord.builder()
                .itemId(item.getId())
                .versionId(version == null ? null : version.getId())
                .key(item.getPromptKey())
                .name(safe(item.getPromptName(), fallback == null ? item.getPromptKey() : fallback.getName()))
                .value(version == null ? (fallback == null ? "" : fallback.getValue()) : safe(version.getPromptValue()))
                .category(safe(item.getCategory(), fallback == null ? "" : fallback.getCategory()))
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
                .enabled(bool(item.getEnabled(), true))
                .priority(item.getPriority() == null ? (fallback == null ? 80 : fallback.getPriority()) : item.getPriority())
                .status(safe(item.getStatus(), version == null ? "active" : version.getStatus()))
                .version(version == null ? (fallback == null ? "1.0.0" : fallback.getVersion()) : safe(version.getVersionNo()))
                .versionLabel(version == null ? (fallback == null ? "" : fallback.getVersionLabel()) : safe(version.getVersionLabel()))
                .changeNote(version == null ? (fallback == null ? "" : fallback.getChangeNote()) : safe(version.getChangeNote()))
                .build();
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
