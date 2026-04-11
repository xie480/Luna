package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

@Data
/**
 * 提示词版本切换请求模型，负责指定要操作的提示词键和目标版本，
 * 供版本激活、回滚和归档接口复用。
 */
public class PromptVersionSwitchRequest {
    /**
     * 目标版本标识。
     */
    private Long versionId;
    /**
     * 目标提示词键。
     */
    private String key;
}
