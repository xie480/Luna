package org.yilena.luna.context;

import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;

public interface SummaryAgent {
    SummaryResult summarize(String userInput,
                            String assistantReply,
                            StructuredContextPackage contextPackage,
                            List<EvidenceBlock> activeEvidenceBlocks,
                            List<String> activeMcpResourceHints,
                            ToolSemanticResult latestToolSemanticResult);
}
