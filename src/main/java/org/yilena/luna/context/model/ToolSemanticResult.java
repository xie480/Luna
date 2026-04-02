package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class ToolSemanticResult {
    String toolStatus;
    List<String> keyFacts;
    String businessImpact;
    List<String> unresolvedIssues;
    String nextStepHint;
    double confidence;
    Map<String, Object> semanticPayload;
}

