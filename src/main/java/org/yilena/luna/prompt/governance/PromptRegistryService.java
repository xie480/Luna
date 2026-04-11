package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提示词注册表服务接口，负责管理运行时可用提示词的读取、存在性判断和分类分组查询，
 * 是提示词治理到运行时消费之间的注册表入口。
 */
public interface PromptRegistryService {
    Optional<PromptItemRecord> getByKey(String key);

    default Optional<PromptItemRecord> getByKeyIncludingDisabled(String key) {
        return getByKey(key);
    }

    Optional<PromptItemRecord> getById(Long id);

    default Optional<PromptItemRecord> getByIdIncludingDisabled(Long id) {
        return getById(id);
    }

    boolean existsByKey(String key);

    List<PromptItemRecord> listAllActive();

    default List<PromptItemRecord> listAll(boolean includeDisabled) {
        return listAllActive();
    }

    List<PromptItemRecord> listByCategory(String category, String subCategory);

    Map<String, String> listKeyValueByCategory(String category);

    List<String> listCategories();

    String resolvePromptValue(String key, String fallbackValue);
}
