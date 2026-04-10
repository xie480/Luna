package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * 该模型用于承载摘要代理输出结果，包含叙事型摘要和状态快照两部分内容。
 */
@Value
@Builder
public class SummaryResult {
    /**
     * 面向模型理解的自然语言摘要。
     */
    String narrativeSummary;
    /**
     * 供状态恢复和上下文组装使用的结构化快照。
     */
    Map<String, Object> stateSnapshot;
}
