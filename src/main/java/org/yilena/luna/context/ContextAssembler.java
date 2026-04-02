package org.yilena.luna.context;

import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;

public interface ContextAssembler {
    AssembledContext assemble(StructuredContextPackage contextPackage,
                              InputReconstructionResult reconstructionResult,
                              ContextRerankResult rerankResult,
                              ToolSemanticResult toolSemanticResult,
                              String userInput,
                              List<String> memorySnippets,
                              List<String> knowledgeSnippets,
                              List<String> preferenceSnippets,
                              List<String> longTermMemorySnippets,
                              String toolContext);
}

