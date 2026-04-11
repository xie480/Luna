package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

import java.util.List;

@Data
/**
 * 提示策略保存请求模型，负责承载提示策略包的基础信息、包含排除项和版本说明，
 * 供治理后台提交策略保存时使用。
 */
public class PromptPolicySaveRequest {
    /**
     * 策略唯一标识。
     */
    private String policyId;
    /**
     * 策略展示名称。
     */
    private String policyName;
    /**
     * 策略用途说明。
     */
    private String description;
    /**
     * 策略是否启用。
     */
    private Boolean enabled;
    /**
     * 本策略显式包含的提示词键列表。
     */
    private List<String> includePromptKeys;
    /**
     * 本策略显式排除的提示词键列表。
     */
    private List<String> excludePromptKeys;
    /**
     * 本次保存的版本号或版本标记。
     */
    private String version;
    /**
     * 本次变更说明。
     */
    private String changeNote;
}
