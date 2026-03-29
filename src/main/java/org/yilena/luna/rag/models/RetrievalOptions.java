package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
/**
 * 检索可选参数。
 */
public class RetrievalOptions {
    // 是否打开调试模式（预留）
    @Builder.Default
    boolean debug = false;

    // 最大允许时延（毫秒）
    @Builder.Default
    long maxLatencyMs = 2500;
}
