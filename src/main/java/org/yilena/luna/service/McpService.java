package org.yilena.luna.service;

import org.yilena.luna.entity.McpPromptDescriptor;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceDescriptor;
import org.yilena.luna.entity.McpResourceResult;
import org.yilena.luna.entity.McpPromptCatalog;
import org.yilena.luna.entity.McpResourceCatalog;
import org.yilena.luna.entity.McpServerRegistry;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.McpToolDescriptor;
import org.yilena.luna.entity.McpToolCatalog;
import org.yilena.luna.entity.McpToolImplMapping;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.WorkflowTemplate;

import java.util.List;
import java.util.Map;

/**
 * MCP 能力服务接口，负责统一管理远端与本地 MCP 资源目录、协议调用以及迁移期目录写入，
 * 是系统检索与调用 MCP 能力的核心服务入口。
 */
public interface McpService {

    /**
     * 统一查询宿主侧可用能力资源列表。
     */
    List<Resource> listAll();

    Resource getResourceById(Long id);

    List<Resource> searchResources(String query);

    /**
     * 按 MCP 协议风格列出指定服务端的工具能力。
     */
    List<McpToolDescriptor> listTools(String serverCode);

    McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson);

    List<McpPromptDescriptor> listPrompts(String serverCode);

    McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson);

    List<McpResourceDescriptor> listResources(String serverCode);

    McpResourceResult readResource(String serverCode, String resourceUri);

    Map<String, Object> syncCapabilityCatalog();

    /**
     * 迁移期写入 MCP 服务注册信息，按完整字段执行 upsert。
     */
    McpServerRegistry upsertServerRegistry(McpServerRegistry registry);

    McpToolCatalog upsertToolCatalog(McpToolCatalog toolCatalog);

    McpToolImplMapping upsertToolImplMapping(McpToolImplMapping mapping);

    McpPromptCatalog upsertPromptCatalog(McpPromptCatalog promptCatalog);

    McpResourceCatalog upsertResourceCatalog(McpResourceCatalog resourceCatalog);

    WorkflowTemplate upsertWorkflowTemplate(WorkflowTemplate workflowTemplate);
}
