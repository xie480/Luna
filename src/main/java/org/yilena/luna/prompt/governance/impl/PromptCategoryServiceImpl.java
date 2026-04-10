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

/**
 * Prompt 分类服务实现，负责读取分类目录、构建分类树，并判定分类是否属于执行类或允许关键词匹配。
 */
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
        /**
         * 优先按启用状态和排序规则读取分类，为后续目录展示和分类匹配提供稳定顺序。
         */
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
        /**
         * 先把启用分类转换为节点索引，再按父子关系拼装成树形结构供前端展示。
         */
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
        /**
         * 按分类键精确查询分类记录，供写入校验和分类能力判断复用。
         */
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
        /**
         * 优先使用数据库配置判断执行类分类，缺失时再退回内置兜底集合。
         */
        Optional<PromptCategoryEntity> category = findByKey(categoryKey);
        if (category.isPresent()) {
            return Boolean.TRUE.equals(category.get().getIsExecutionCategory());
        }
        return categoryKey != null && EXECUTION_CATEGORY_FALLBACK.contains(categoryKey.trim().toLowerCase());
    }

    @Override
    public boolean isKeywordMatchAllowed(String categoryKey) {
        /**
         * 关键词匹配能力优先取分类配置，没有配置时按是否执行类分类做默认决策。
         */
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
