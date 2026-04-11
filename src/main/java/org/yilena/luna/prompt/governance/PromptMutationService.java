package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

/**
 * 提示词变更服务接口，负责处理提示词创建、更新和删除操作，
 * 在治理链路中承担提示词内容变更入口职责。
 */
public interface PromptMutationService {
    PromptItemRecord create(PromptUpsertRequest request);

    PromptItemRecord update(PromptUpsertRequest request);

    void deleteByKey(String key);
}
