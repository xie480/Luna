package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ToolState {
    String lastToolName;
    String lastToolInput;
    String lastToolStatus;
    String lastToolRawResultRef;
    String lastToolRawResultDigest;
    String lastToolRawResultPreview;
    String lastToolSemanticSummary;
    List<String> toolCallHistoryRefs;
}
