package org.yilena.luna.service;

import org.yilena.luna.entity.McpPromptDescriptor;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceDescriptor;
import org.yilena.luna.entity.McpResourceResult;
import org.yilena.luna.entity.McpSkill;
import org.yilena.luna.entity.McpPromptCatalog;
import org.yilena.luna.entity.McpResourceCatalog;
import org.yilena.luna.entity.McpServerRegistry;
import org.yilena.luna.entity.McpTool;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.McpToolDescriptor;
import org.yilena.luna.entity.McpToolCatalog;
import org.yilena.luna.entity.McpToolImplMapping;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.WorkflowTemplate;

import java.util.List;
import java.util.Map;

public interface McpService {

    // Legacy registration API (kept for compatibility).
    McpTool registerTool(McpTool tool);

    McpTool updateTool(McpTool tool);

    void deleteTool(Long id);

    McpSkill registerSkill(McpSkill skill);

    McpSkill updateSkill(McpSkill skill);

    void deleteSkill(Long id);

    // Unified host capability retrieval.
    List<Resource> listAll();

    Resource getResourceById(Long id);

    List<Resource> searchResources(String query);

    // MCP protocol style API.
    List<McpToolDescriptor> listTools(String serverCode);

    McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson);

    List<McpPromptDescriptor> listPrompts(String serverCode);

    McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson);

    List<McpResourceDescriptor> listResources(String serverCode);

    McpResourceResult readResource(String serverCode, String resourceUri);

    // Sync json/tool and json/skill into catalog tables.
    Map<String, Object> syncCatalogFromJson();

    // Migration upsert APIs (full-field)
    McpServerRegistry upsertServerRegistry(McpServerRegistry registry);

    McpToolCatalog upsertToolCatalog(McpToolCatalog toolCatalog);

    McpToolImplMapping upsertToolImplMapping(McpToolImplMapping mapping);

    McpPromptCatalog upsertPromptCatalog(McpPromptCatalog promptCatalog);

    McpResourceCatalog upsertResourceCatalog(McpResourceCatalog resourceCatalog);

    WorkflowTemplate upsertWorkflowTemplate(WorkflowTemplate workflowTemplate);
}
