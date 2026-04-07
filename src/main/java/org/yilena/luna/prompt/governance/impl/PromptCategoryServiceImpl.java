package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.mapper.PromptCategoryMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PromptCategoryServiceImpl implements PromptCategoryService {

    private static final Set<String> EXECUTION_CATEGORY_FALLBACK = Set.of(
            "tool", "repair", "summary", "guardrail", "agent-local", "task", "system"
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
}
