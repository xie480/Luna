package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class EvidenceBlock {
    String blockId;
    String sourceType;
    String title;
    String content;
    Double score;
    Map<String, Object> metadata;
}

