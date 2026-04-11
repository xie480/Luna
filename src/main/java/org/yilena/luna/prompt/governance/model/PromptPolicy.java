package org.yilena.luna.prompt.governance.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * 提示策略模型，负责描述一个提示策略包的基础信息与包含项，
 * 用于在运行时按策略批量控制提示词生效范围。
 */
public class PromptPolicy {
    /**
     * 策略唯一标识。
     */
    private String policyId;
    /**
     * 策略名称。
     */
    private String name;
    /**
     * 策略描述说明。
     */
    private String description;
    /**
     * 本策略包含的提示词项列表。
     */
    private List<String> includeItems;
    /**
     * 策略是否启用。
     */
    private Boolean enabled;
}
