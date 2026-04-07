package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PromptRegistryService {
    Optional<PromptItemRecord> getByKey(String key);

    Optional<PromptItemRecord> getById(Long id);

    boolean existsByKey(String key);

    List<PromptItemRecord> listAllActive();

    List<PromptItemRecord> listByCategory(String category, String subCategory);

    Map<String, String> listKeyValueByCategory(String category);

    List<String> listCategories();

    String resolvePromptValue(String key, String fallbackValue);
}
