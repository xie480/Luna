package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;
import java.util.Map;

public interface PromptFrontendAdapter {
    Map<String, Object> toCategoryKeyValueView(String category, String subCategory, List<PromptItemRecord> rows);
}

