package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;

import java.util.List;
import java.util.Map;

/**
 * 提示词版本服务接口，负责提示词版本列表、详情、切换、回滚、草稿和对比能力，
 * 支撑提示词治理过程中的版本化管理。
 */
public interface PromptVersionService {
    List<PromptItemVersionEntity> listVersions(String key);

    PromptItemVersionEntity getVersionDetail(Long versionId);

    void activateVersion(Long versionId);

    void rollbackToVersion(String key, Long versionId);

    PromptItemVersionEntity saveDraft(String key, PromptUpsertRequest request);

    void archiveVersion(Long versionId);

    Map<String, Object> diff(Long leftVersionId, Long rightVersionId);
}
