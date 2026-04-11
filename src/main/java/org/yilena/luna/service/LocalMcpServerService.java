package org.yilena.luna.service;

import org.yilena.luna.entity.McpPromptDescriptor;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceDescriptor;
import org.yilena.luna.entity.McpResourceResult;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.McpToolDescriptor;

import java.util.List;

/**
 * 本地 MCP 服务端执行门面接口，负责以统一的服务端边界暴露本地工具、提示词和资源能力，
 * 让宿主侧适配器能够按 MCP 风格访问本地实现。
 */
public interface LocalMcpServerService {

    List<McpToolDescriptor> listTools(String serverCode);

    McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson);

    List<McpPromptDescriptor> listPrompts(String serverCode);

    McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson);

    List<McpResourceDescriptor> listResources(String serverCode);

    McpResourceResult readResource(String serverCode, String resourceUri);
}
