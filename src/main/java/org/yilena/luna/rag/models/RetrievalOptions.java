package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
/**
 * RAG retrieval options.
 */
public class RetrievalOptions {

    /**
     * Enable debug metadata in retrieval response.
     */
    @Builder.Default
    boolean debug = false;

    /**
     * Max latency budget in milliseconds.
     */
    @Builder.Default
    long maxLatencyMs = 2500;
}
