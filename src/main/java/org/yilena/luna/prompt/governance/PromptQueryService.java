package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.dto.PromptSearchRequest;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PromptQueryService {
    List<String> listCategories();

    Map<String, String> listKeyValueByCategory(String category, String subCategory);

    List<PromptItemRecord> search(PromptSearchRequest request);

    Optional<PromptItemRecord> detailByKey(String key);
}

