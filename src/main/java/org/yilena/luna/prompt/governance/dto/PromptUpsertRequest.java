package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class PromptUpsertRequest {
    private String key;
    private String promptName;
    private String value;
    private String category;
    private String subCategory;
    private String description;
    private String runtimeSlot;
    private Boolean hasTemplateVariables;
    private List<String> templateVariables;
    private Boolean keywordMatchEnabled;
    private List<String> matchKeywords;
    private String assemblyMode;
    private Map<String, Object> matchScope;
    private Map<String, Object> editPolicy;
    private Boolean enabled;
    private Integer priority;
    private String status;
    private String version;
    private String versionLabel;
    private String changeNote;
}
