package org.yilena.luna.prompt.governance.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptQueryService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.dto.PromptSearchRequest;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prompt 查询服务实现，负责提供分类浏览、键值列表、条件搜索和详情查询能力。
 */
@Service
@RequiredArgsConstructor
public class PromptQueryServiceImpl implements PromptQueryService {

    private final PromptRegistryService promptRegistryService;

    @Override
    public List<String> listCategories() {
        /**
         * 直接复用注册中心的分类视图，保证查询端与运行时可见分类保持一致。
         */
        return promptRegistryService.listCategories();
    }

    @Override
    public Map<String, String> listKeyValueByCategory(String category, String subCategory) {
        /**
         * 按分类输出键值映射，主要服务于配置面板和简化版选择器场景。
         */
        Map<String, String> out = new LinkedHashMap<>();
        for (PromptItemRecord item : promptRegistryService.listByCategory(category, subCategory)) {
            out.put(item.getKey(), item.getValue());
        }
        return out;
    }

    @Override
    public List<PromptItemRecord> search(PromptSearchRequest request) {
        /**
         * 搜索流程先标准化分页与过滤条件，再从注册中心全量视图中过滤出目标数据页。
         */
        PromptSearchRequest effective = request == null ? new PromptSearchRequest() : request;
        String category = firstNonBlank(effective.getCategory(), effective.getCategoryKey());
        long pageNo = effective.getPageNo() == null || effective.getPageNo() <= 0 ? 1L : effective.getPageNo();
        long pageSize = effective.getPageSize() == null || effective.getPageSize() <= 0 ? 20L : Math.min(200L, effective.getPageSize());
        boolean includeDisabled = Boolean.TRUE.equals(effective.getIncludeDisabled())
                || Boolean.FALSE.equals(effective.getEnabled());
        List<PromptItemRecord> filtered = promptRegistryService.listAll(includeDisabled).stream()
                .filter(item -> matchText(item.getCategory(), category))
                .filter(item -> matchText(item.getSubCategory(), effective.getSubCategory()))
                .filter(item -> containsText(item.getKey(), effective.getKeyLike()))
                .filter(item -> containsText(item.getName(), effective.getNameLike()))
                .filter(item -> containsText(item.getValue(), effective.getValueLike()))
                .filter(item -> effective.getHasTemplateVariables() == null || item.isHasTemplateVariables() == effective.getHasTemplateVariables())
                .filter(item -> effective.getKeywordMatchEnabled() == null || item.isKeywordMatchEnabled() == effective.getKeywordMatchEnabled())
                .filter(item -> matchText(item.getAssemblyMode(), effective.getAssemblyMode()))
                .filter(item -> effective.getEnabled() == null || item.isEnabled() == effective.getEnabled())
                .toList();
        int start = (int) ((pageNo - 1) * pageSize);
        if (start >= filtered.size()) {
            return List.of();
        }
        int end = (int) Math.min(filtered.size(), start + pageSize);
        return filtered.subList(start, end);
    }

    @Override
    public Optional<PromptItemRecord> detailByKey(String key) {
        /**
         * 按键查询 Prompt 详情，供详情页或编辑页回显使用。
         */
        return promptRegistryService.getByKey(key);
    }

    private boolean matchText(String value, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return value != null && value.equalsIgnoreCase(expected.trim());
    }

    private boolean containsText(String value, String like) {
        if (like == null || like.isBlank()) {
            return true;
        }
        return value != null && value.toLowerCase().contains(like.trim().toLowerCase());
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }
}
