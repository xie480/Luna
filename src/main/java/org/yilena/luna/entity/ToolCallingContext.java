package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Tool Calling 期间的续跑上下文
 * 在 ChatService -> Agent -> ApprovalService 之间透传
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallingContext {
    private String chatSessionKey;
    private String userInput;
    private String toolDecisionInput;
    private String assembledDecisionContext;
    private List<String> memorySnippets;
    private List<String> knowledgeSnippets;
    private List<String> preferenceSnippets;
    private List<String> longTermMemorySnippets;
    private List<Resource> executionCandidates;
    private List<String> mcpResourceHints;
    private List<Map<String, Object>> toolExecutionTraces;
}
