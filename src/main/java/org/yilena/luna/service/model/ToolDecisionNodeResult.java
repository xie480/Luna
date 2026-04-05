package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.ToolSemanticResult;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class ToolDecisionNodeResult {
    String toolContext;
    Map<String, Object> rawToolResultChannel;
    List<String> toolTraceRefs;
    ToolSemanticResult toolSemantic;
    String preToolSnapshotId;
    String toolDecisionSnapshotId;
}

