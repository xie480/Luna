package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Tool Calling 期间的续跑上下文
 * 在 ChatService -> Agent -> ApprovalService 之间透传
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallingContext {
    private String chatSessionKey; // 声明成员字段
    private String userInput; // 声明成员字段
    private List<String> memorySnippets; // 声明成员字段
    private List<String> knowledgeSnippets; // 声明成员字段
    private List<String> preferenceSnippets; // 声明成员字段
    private List<String> longTermMemorySnippets; // 声明成员字段
} // 结束当前代码块
