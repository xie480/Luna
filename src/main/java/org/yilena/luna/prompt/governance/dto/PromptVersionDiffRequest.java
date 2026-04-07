package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

@Data
public class PromptVersionDiffRequest {
    private Long leftVersionId;
    private Long rightVersionId;
}
