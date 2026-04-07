package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

@Data
public class PromptSearchRequest {
    private String category;
    private String subCategory;
    private String keyLike;
    private String valueLike;
    private Boolean hasTemplateVariables;
    private Boolean keywordMatchEnabled;
    private String assemblyMode;
    private Boolean enabled;
    private Long pageNo = 1L;
    private Long pageSize = 20L;
}

