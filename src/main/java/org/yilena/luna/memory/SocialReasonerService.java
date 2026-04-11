package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;

import java.util.Map;

/**
 * 社交推理服务接口，负责结合用户输入、关系状态与关系记忆生成陪伴侧草稿信息，
 * 为回复中的情绪表达和关系维护提供推理结果。
 */
public interface SocialReasonerService {
    Map<String, Object> buildRelationalDraft(String sessionId,
                                             String userInput,
                                             RelationalRuntimeState relationalState,
                                             Map<String, Object> relationalContext);
}
