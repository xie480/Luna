package org.yilena.luna.prompt.governance.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class PromptSearchRequest {
    @JsonAlias({"category_key"})
    private String category;
    private String categoryKey;
    private String subCategory;
    private String keyLike;
    private String nameLike;
    private String valueLike;
    private Boolean hasTemplateVariables;
    private Boolean keywordMatchEnabled;
    private String assemblyMode;
    private Boolean enabled;
    private Long pageNo = 1L;
    private Long pageSize = 20L;
}
