package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;

import java.util.List;
import java.util.Map;

public interface PromptVersionService {
    List<PromptItemVersionEntity> listVersions(String key);

    PromptItemVersionEntity getVersionDetail(Long versionId);

    void activateVersion(Long versionId);

    void rollbackToVersion(String key, Long versionId);

    PromptItemVersionEntity saveDraft(String key, PromptUpsertRequest request);

    void archiveVersion(Long versionId);

    Map<String, Object> diff(Long leftVersionId, Long rightVersionId);
}
