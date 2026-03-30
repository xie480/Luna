package org.yilena.luna.adapter;

import org.yilena.luna.entity.McpPromptDescriptor;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceDescriptor;
import org.yilena.luna.entity.McpResourceResult;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.McpToolDescriptor;

import java.util.List;

/**
 * MCP client abstraction used by host-side orchestration.
 */
public interface McpClientAdapter {

    List<McpToolDescriptor> listTools(String serverCode); // 执行语句逻辑

    McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson); // 执行语句逻辑

    List<McpPromptDescriptor> listPrompts(String serverCode); // 执行语句逻辑

    McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson); // 执行语句逻辑

    List<McpResourceDescriptor> listResources(String serverCode); // 执行语句逻辑

    McpResourceResult readResource(String serverCode, String resourceUri); // 执行语句逻辑
} // 结束当前代码块
