package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

public interface PromptMutationService {
    PromptItemRecord create(PromptUpsertRequest request);

    PromptItemRecord update(PromptUpsertRequest request);

    void deleteByKey(String key);
}

