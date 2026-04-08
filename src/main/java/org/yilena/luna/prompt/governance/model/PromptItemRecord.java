package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PromptItemRecord {
    Long itemId;
    Long versionId;
    String key;
    String name;
    String value;
    String category;
    String subCategory;
    String description;
    String runtimeSlot;
    boolean hasTemplateVariables;
    List<String> templateVariables;
    boolean keywordMatchEnabled;
    List<String> matchKeywords;
    String assemblyMode;
    MatchScope matchScope;
    EditPolicy editPolicy;
    boolean enabled;
    Integer priority;
    String status;
    String version;
    String versionLabel;
    String changeNote;
}
