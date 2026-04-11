package org.yilena.luna.adapter;

import org.yilena.luna.entity.McpPromptDescriptor;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceDescriptor;
import org.yilena.luna.entity.McpResourceResult;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.McpToolDescriptor;

import java.util.List;

/**
 * MCP 客户端适配器接口，负责抽象工具、提示词和资源三类 MCP 能力的统一调用方式。
 */
public interface McpClientAdapter {

    /**
     * 列出指定 MCP 服务暴露的工具描述。
     */
    List<McpToolDescriptor> listTools(String serverCode);

    /**
     * 调用指定 MCP 工具并返回原始执行结果。
     */
    McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson);

    /**
     * 列出指定 MCP 服务暴露的提示词模板。
     */
    List<McpPromptDescriptor> listPrompts(String serverCode);

    /**
     * 获取指定 MCP 提示词模板的展开结果。
     */
    McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson);

    /**
     * 列出指定 MCP 服务暴露的资源描述。
     */
    List<McpResourceDescriptor> listResources(String serverCode);

    /**
     * 读取指定 MCP 资源内容。
     */
    McpResourceResult readResource(String serverCode, String resourceUri);
}
