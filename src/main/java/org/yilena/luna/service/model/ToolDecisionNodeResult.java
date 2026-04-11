package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.ToolSemanticResult;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 工具决策节点结果模型，负责汇总工具决策阶段生成的上下文、语义结果和快照引用，
 * 供后续主模型或状态写回阶段承接使用。
 */
public class ToolDecisionNodeResult {
    /**
     * 组装后的工具上下文文本。
     */
    String toolContext;
    /**
     * 原始工具结果通道数据。
     */
    Map<String, Object> rawToolResultChannel;
    /**
     * 工具调用轨迹引用列表。
     */
    List<String> toolTraceRefs;
    /**
     * 工具语义分析结果。
     */
    ToolSemanticResult toolSemantic;
    /**
     * 工具决策前上下文快照标识。
     */
    String preToolSnapshotId;
    /**
     * 工具决策上下文快照标识。
     */
    String toolDecisionSnapshotId;
}
