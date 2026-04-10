package org.yilena.luna.context;

import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;

/**
 * 摘要代理接口，负责将当前轮次对话压缩为叙事摘要和状态快照。
 */
public interface SummaryAgent {
    /**
     * 对当前轮次上下文生成摘要结果。
     */
    SummaryResult summarize(String userInput,
                            String assistantReply,
                            StructuredContextPackage contextPackage,
                            List<EvidenceBlock> activeEvidenceBlocks,
                            List<String> activeMcpResourceHints,
                            ToolSemanticResult latestToolSemanticResult);
}
