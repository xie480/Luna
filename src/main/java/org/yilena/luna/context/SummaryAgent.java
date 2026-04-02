package org.yilena.luna.context;

import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.model.StructuredContextPackage;

public interface SummaryAgent {
    SummaryResult summarize(String userInput, String assistantReply, StructuredContextPackage contextPackage);
}

