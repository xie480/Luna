package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
/**
 * 被拒绝提示词模型，负责记录某条提示词未通过匹配筛选时的键和值，
 * 便于预览和调试阶段解释未命中原因。
 */
public class RejectedPromptItem {
    /**
     * 被拒绝的提示词键。
     */
    String key;
    /**
     * 被拒绝原因说明。
     */
    String rejectedReason;
}
