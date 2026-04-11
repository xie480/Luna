package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
/**
 * 提示策略详情视图模型，负责向治理后台输出策略包详情、当前版本和包含排除项信息，
 * 便于前端直接展示策略配置状态。
 */
public class PromptPolicyDetailView {
    /**
     * 策略主键标识。
     */
    Long id;
    /**
     * 策略业务标识。
     */
    String policyId;
    /**
     * 策略名称。
     */
    String policyName;
    /**
     * 策略说明。
     */
    String description;
    /**
     * 策略是否启用。
     */
    boolean enabled;
    /**
     * 当前激活版本标识。
     */
    Long currentVersionId;
    /**
     * 当前激活版本号。
     */
    String currentVersionNo;
    /**
     * 当前版本包含的提示词键列表。
     */
    List<String> includePromptKeys;
    /**
     * 当前版本排除的提示词键列表。
     */
    List<String> excludePromptKeys;
}
