package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.mapper.PromptCategoryMapper;
import org.yilena.luna.prompt.governance.model.PromptCategoryTreeNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PromptCategoryServiceImpl implements PromptCategoryService {

    private static final Set<String> EXECUTION_CATEGORY_FALLBACK = Set.of(
            "tool", "repair", "summary", "guardrail", "agent-local", "task", "system",
            "memory-hint", "rag-hint", "format"
    );

    private final PromptCategoryMapper promptCategoryMapper;

    @Override
    public List<PromptCategoryEntity> listEnabledOrdered() {
        try {
            return promptCategoryMapper.selectList(
                    new LambdaQueryWrapper<PromptCategoryEntity>()
                            .eq(PromptCategoryEntity::getEnabled, true)
                            .orderByDesc(PromptCategoryEntity::getSortOrder)
                            .orderByAsc(PromptCategoryEntity::getCategoryKey)
            );
        } catch (Exception ignore) {
            return List.of();
        }
    }

    @Override
    public List<PromptCategoryTreeNode> listEnabledTree() {
        List<PromptCategoryEntity> categories = listEnabledOrdered();
        if (categories.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, PromptCategoryTreeNode> nodes = new LinkedHashMap<>();
        for (PromptCategoryEntity category : categories) {
            if (category == null || category.getCategoryKey() == null || category.getCategoryKey().isBlank()) {
                continue;
            }
            String categoryKey = category.getCategoryKey().trim();
            nodes.put(categoryKey, PromptCategoryTreeNode.builder()
                    .categoryKey(categoryKey)
                    .categoryName(safe(category.getCategoryName()))
                    .parentCategoryKey(safe(category.getParentCategoryKey()))
                    .sortOrder(category.getSortOrder() == null ? 0 : category.getSortOrder())
                    .keywordMatchAllowed(!Boolean.FALSE.equals(category.getKeywordMatchAllowed()))
                    .executionCategory(Boolean.TRUE.equals(category.getIsExecutionCategory()))
                    .enabled(!Boolean.FALSE.equals(category.getEnabled()))
                    .children(new ArrayList<>())
                    .build());
        }
        List<PromptCategoryTreeNode> roots = new ArrayList<>();
        for (PromptCategoryTreeNode node : nodes.values()) {
            if (node == null) {
                continue;
            }
            String parent = node.getParentCategoryKey() == null ? "" : node.getParentCategoryKey().trim();
            if (parent.isBlank() || !nodes.containsKey(parent)) {
                roots.add(node);
                continue;
            }
            nodes.get(parent).getChildren().add(node);
        }
        return roots;
    }

    @Override
    public Optional<PromptCategoryEntity> findByKey(String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) {
            return Optional.empty();
        }
        try {
            PromptCategoryEntity entity = promptCategoryMapper.selectOne(
                    new LambdaQueryWrapper<PromptCategoryEntity>()
                            .eq(PromptCategoryEntity::getCategoryKey, categoryKey.trim())
                            .last("limit 1")
            );
            return Optional.ofNullable(entity);
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isExecutionCategory(String categoryKey) {
        Optional<PromptCategoryEntity> category = findByKey(categoryKey);
        if (category.isPresent()) {
            return Boolean.TRUE.equals(category.get().getIsExecutionCategory());
        }
        return categoryKey != null && EXECUTION_CATEGORY_FALLBACK.contains(categoryKey.trim().toLowerCase());
    }

    @Override
    public boolean isKeywordMatchAllowed(String categoryKey) {
        Optional<PromptCategoryEntity> category = findByKey(categoryKey);
        if (category.isPresent()) {
            return !Boolean.FALSE.equals(category.get().getKeywordMatchAllowed());
        }
        return !isExecutionCategory(categoryKey);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
