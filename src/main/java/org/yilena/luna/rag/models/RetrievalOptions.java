package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

/**
 * 该模型用于描述一次 RAG 检索请求的附加选项，例如调试开关和延迟预算。
 */
@Value
@Builder
public class RetrievalOptions {

    /**
     * 是否在检索响应中附带调试元数据。
     */
    @Builder.Default
    boolean debug = false;

    /**
     * 检索最大延迟预算，单位毫秒。
     */
    @Builder.Default
    long maxLatencyMs = 2500;
}
