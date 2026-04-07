package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;

import java.util.List;

public interface PromptVersionService {
    List<PromptItemVersionEntity> listVersions(String key);

    void activateVersion(Long versionId);

    void rollbackToVersion(String key, Long versionId);
}

