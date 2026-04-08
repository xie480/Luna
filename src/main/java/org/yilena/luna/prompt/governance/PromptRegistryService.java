package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
