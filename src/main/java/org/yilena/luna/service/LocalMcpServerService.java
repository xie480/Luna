package org.yilena.luna.service;

import org.yilena.luna.entity.McpPromptDescriptor;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceDescriptor;
import org.yilena.luna.entity.McpResourceResult;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.McpToolDescriptor;

import java.util.List;

/**
 * Local MCP server-side execution facade.
 * Host-side adapter should reach local capabilities through this server boundary.
 */
public interface LocalMcpServerService {

    List<McpToolDescriptor> listTools(String serverCode);

    McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson);

    List<McpPromptDescriptor> listPrompts(String serverCode);

    McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson);

    List<McpResourceDescriptor> listResources(String serverCode);

    McpResourceResult readResource(String serverCode, String resourceUri);
}

