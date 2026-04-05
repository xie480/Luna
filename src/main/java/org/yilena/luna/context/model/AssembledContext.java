package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class AssembledContext {
    String prompt;
    Map<String, List<String>> sections;
    Map<String, List<String>> canonicalSections;
    Map<String, List<String>> candidatePool;
    Map<String, Integer> sectionTokenCounts;
    Map<String, Double> sectionTokenRatios;
    String snapshotId;
}
