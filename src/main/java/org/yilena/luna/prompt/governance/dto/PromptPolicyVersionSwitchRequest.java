package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

@Data
/**
 * 提示策略版本切换请求模型，负责指定目标策略及其要激活的版本标识，
 * 供治理后台切换策略版本时使用。
 */
public class PromptPolicyVersionSwitchRequest {
    /**
     * 目标策略标识。
     */
    private String policyId;
    /**
     * 目标版本标识。
     */
    private Long versionId;
}
