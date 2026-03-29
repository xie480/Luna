package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Approval task context persisted in Redis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String sessionId;

    // Display name for the capability being approved.
    private String skillName;

    // New MCP routing fields.
    private String serverCode;
    private String toolName;

    // Legacy routing fields for backward compatibility.
    private String beanName;
    private String methodName;

    private String argsJson;
    private Long createTime;

    // Chat continuation context.
    private String chatSessionKey;
    private String userInput;
    private List<String> memorySnippets;
    private List<String> knowledgeSnippets;
}
