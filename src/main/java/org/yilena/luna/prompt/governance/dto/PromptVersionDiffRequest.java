package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

@Data
/**
 * 提示词版本对比请求模型，负责指定左右两个版本标识，
 * 供治理后台生成版本差异结果时使用。
 */
public class PromptVersionDiffRequest {
    /**
     * 左侧版本标识。
     */
    private Long leftVersionId;
    /**
     * 右侧版本标识。
     */
    private Long rightVersionId;
}
