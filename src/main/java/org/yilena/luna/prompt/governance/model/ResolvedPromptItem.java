package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ResolvedPromptItem {
    Long itemId;
    Long versionId;
    String key;
    String name;
    String value;
    String category;
    String subCategory;
    String description;
    String runtimeSlot;
    String assemblyMode;
    String matchReason;
    boolean hasTemplateVariables;
    boolean keywordMatchEnabled;
    Integer priority;
    String version;
    String versionLabel;
    String assemblerVersion;
}
