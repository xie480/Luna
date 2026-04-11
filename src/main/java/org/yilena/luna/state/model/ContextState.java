package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 上下文状态模型，负责保存当前会话已激活的上下文摘要、证据引用和最近快照标识，
 * 供后续轮次复用最新上下文边界。
 */
public class ContextState {
    /**
     * 最近一次生成的叙事型摘要。
     */
    String latestNarrativeSummary;
    /**
     * 最近一次结构化状态快照内容。
     */
    Map<String, Object> latestStateSnapshot;
    /**
     * 当前生效的知识证据引用集合。
     */
    List<String> activeKnowledgeRefs;
    /**
     * 当前生效的记忆证据引用集合。
     */
    List<String> activeMemoryRefs;
    /**
     * 当前生效的工具证据引用集合。
     */
    List<String> activeToolEvidenceRefs;
    /**
     * 当前生效的 MCP 提示词引用集合。
     */
    List<String> activeMcpPromptRefs;
    /**
     * 当前生效的 MCP 资源引用集合。
     */
    List<String> activeMcpResourceRefs;
    /**
     * 当前生效的 MCP 工作流引用集合。
     */
    List<String> activeMcpWorkflowRefs;
    /**
     * 当前生效的 MCP 工具引用集合。
     */
    List<String> activeMcpToolRefs;
    /**
     * 最近一次上下文快照标识。
     */
    String latestContextSnapshotId;
}
