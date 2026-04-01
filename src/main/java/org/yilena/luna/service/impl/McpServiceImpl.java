package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.McpClientAdapter;
import org.yilena.luna.constants.McpConstant;
import org.yilena.luna.entity.*;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.enums.Sensitivity;
import org.yilena.luna.mapper.*;
import org.yilena.luna.service.McpService;
import org.yilena.luna.utils.LlmClientUtil;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * MCP 鐠у嫭绨惄顔肩秿閺堝秴濮熺€圭偟骞囬敍? * 缂佺喍绔寸紒瀛樺Б瀹搞儱鍙块妴浣瑰絹缁€楦跨槤閵嗕浇绁┃鎰┾偓浣镐紣娴ｆ粍绁﹂惄顔肩秿閿涘苯鑻熼幓鎰返閺堫剙婀?JSON 閸氬本顒為懗钘夊閵? */
public class McpServiceImpl implements McpService {

    private final McpToolCatalogMapper toolCatalogMapper;
    private final McpPromptCatalogMapper promptCatalogMapper;
    private final McpResourceCatalogMapper resourceCatalogMapper;
    private final McpToolImplMappingMapper toolImplMappingMapper;
    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final McpServerRegistryMapper serverRegistryMapper;
    private final McpClientAdapter mcpClientAdapter;
    private final LlmClientUtil llmClientUtil;
    private final ObjectMapper objectMapper;

    @Override
    public List<Resource> listAll() {
        List<Resource> out = new ArrayList<>();
        toolCatalogMapper.selectList(null).forEach(t -> out.add(toTool(t)));
        promptCatalogMapper.selectList(null).forEach(p -> out.add(toPrompt(p)));
        resourceCatalogMapper.selectList(null).forEach(r -> out.add(toResource(r)));
        workflowTemplateMapper.selectList(null).forEach(w -> out.add(toWorkflow(w)));
        out.addAll(buildDomainResourceTemplates());
        return out;
    }

    @Override
    public Resource getResourceById(Long id) {
        if (id == null) return null;
        McpToolCatalog t = toolCatalogMapper.selectById(id);
        if (t != null) return toTool(t);
        McpPromptCatalog p = promptCatalogMapper.selectById(id);
        if (p != null) return toPrompt(p);
        McpResourceCatalog r = resourceCatalogMapper.selectById(id);
        if (r != null) return toResource(r);
        WorkflowTemplate w = workflowTemplateMapper.selectById(id);
        if (w != null) return toWorkflow(w);
        return null;
    }

    @Override
    public List<Resource> searchResources(String query) {
        if (blank(query)) return Collections.emptyList();
        String q = query.toLowerCase(Locale.ROOT);
        return listAll().stream()
                .filter(r -> contains(r.getName(), q) || contains(r.getDescription(), q) || contains(r.getResourceUri(), q))
                .limit(20)
                .toList();
    }

    @Override
    public List<McpToolDescriptor> listTools(String serverCode) {
        return mcpClientAdapter.listTools(serverCode);
    }

    @Override
    public McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson) {
        return mcpClientAdapter.callTool(serverCode, toolName, argumentsJson);
    }

    @Override
    public List<McpPromptDescriptor> listPrompts(String serverCode) {
        return mcpClientAdapter.listPrompts(serverCode);
    }

    @Override
    public McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson) {
        return mcpClientAdapter.getPrompt(serverCode, promptName, argumentsJson);
    }

    @Override
    public List<McpResourceDescriptor> listResources(String serverCode) {
        return mcpClientAdapter.listResources(serverCode);
    }

    @Override
    public McpResourceResult readResource(String serverCode, String resourceUri) {
        return mcpClientAdapter.readResource(serverCode, resourceUri);
    }

    @Override
    public McpServerRegistry upsertServerRegistry(McpServerRegistry registry) {
        if (registry == null || blank(registry.getServerCode())) {
            throw new IllegalArgumentException("serverCode required");
        }
        McpServerRegistry exist = serverRegistryMapper.selectOne(new LambdaQueryWrapper<McpServerRegistry>()
                .eq(McpServerRegistry::getServerCode, registry.getServerCode())
                .last("LIMIT 1"));
        if (exist != null) registry.setId(exist.getId());
        if (registry.getId() == null) serverRegistryMapper.insert(registry);
        else serverRegistryMapper.updateById(registry);
        return registry;
    }

    @Override
    public McpToolCatalog upsertToolCatalog(McpToolCatalog toolCatalog) {
        if (toolCatalog == null || blank(toolCatalog.getServerCode()) || blank(toolCatalog.getToolName())) {
            throw new IllegalArgumentException("serverCode/toolName required");
        }
        McpToolCatalog exist = toolCatalogMapper.selectOne(new LambdaQueryWrapper<McpToolCatalog>()
                .eq(McpToolCatalog::getServerCode, toolCatalog.getServerCode())
                .eq(McpToolCatalog::getToolName, toolCatalog.getToolName())
                .last("LIMIT 1"));
        if (exist != null) toolCatalog.setId(exist.getId());
        if (toolCatalog.getId() == null) toolCatalogMapper.insert(toolCatalog);
        else toolCatalogMapper.updateById(toolCatalog);
        return toolCatalog;
    }

    @Override
    public McpToolImplMapping upsertToolImplMapping(McpToolImplMapping mapping) {
        if (mapping == null || blank(mapping.getServerCode()) || blank(mapping.getToolName())) {
            throw new IllegalArgumentException("serverCode/toolName required");
        }
        McpToolImplMapping exist = toolImplMappingMapper.selectOne(new LambdaQueryWrapper<McpToolImplMapping>()
                .eq(McpToolImplMapping::getServerCode, mapping.getServerCode())
                .eq(McpToolImplMapping::getToolName, mapping.getToolName())
                .last("LIMIT 1"));
        if (exist != null) mapping.setId(exist.getId());
        if (mapping.getId() == null) toolImplMappingMapper.insert(mapping);
        else toolImplMappingMapper.updateById(mapping);
        return mapping;
    }

    @Override
    public McpPromptCatalog upsertPromptCatalog(McpPromptCatalog promptCatalog) {
        if (promptCatalog == null || blank(promptCatalog.getServerCode()) || blank(promptCatalog.getPromptName())) {
            throw new IllegalArgumentException("serverCode/promptName required");
        }
        McpPromptCatalog exist = promptCatalogMapper.selectOne(new LambdaQueryWrapper<McpPromptCatalog>()
                .eq(McpPromptCatalog::getServerCode, promptCatalog.getServerCode())
                .eq(McpPromptCatalog::getPromptName, promptCatalog.getPromptName())
                .last("LIMIT 1"));
        if (exist != null) promptCatalog.setId(exist.getId());
        if (promptCatalog.getId() == null) promptCatalogMapper.insert(promptCatalog);
        else promptCatalogMapper.updateById(promptCatalog);
        return promptCatalog;
    }

    @Override
    public McpResourceCatalog upsertResourceCatalog(McpResourceCatalog resourceCatalog) {
        if (resourceCatalog == null || blank(resourceCatalog.getServerCode()) || blank(resourceCatalog.getResourceUri())) {
            throw new IllegalArgumentException("serverCode/resourceUri required");
        }
        McpResourceCatalog exist = resourceCatalogMapper.selectOne(new LambdaQueryWrapper<McpResourceCatalog>()
                .eq(McpResourceCatalog::getServerCode, resourceCatalog.getServerCode())
                .eq(McpResourceCatalog::getResourceUri, resourceCatalog.getResourceUri())
                .last("LIMIT 1"));
        if (exist != null) resourceCatalog.setId(exist.getId());
        if (resourceCatalog.getId() == null) resourceCatalogMapper.insert(resourceCatalog);
        else resourceCatalogMapper.updateById(resourceCatalog);
        return resourceCatalog;
    }

    @Override
    public WorkflowTemplate upsertWorkflowTemplate(WorkflowTemplate workflowTemplate) {
        if (workflowTemplate == null || blank(workflowTemplate.getWorkflowName())) {
            throw new IllegalArgumentException("workflowName required");
        }
        WorkflowTemplate exist = workflowTemplateMapper.selectOne(new LambdaQueryWrapper<WorkflowTemplate>()
                .eq(WorkflowTemplate::getWorkflowName, workflowTemplate.getWorkflowName())
                .last("LIMIT 1"));
        if (exist != null) workflowTemplate.setId(exist.getId());
        if (workflowTemplate.getId() == null) workflowTemplateMapper.insert(workflowTemplate);
        else workflowTemplateMapper.updateById(workflowTemplate);
        return workflowTemplate;
    }

    private Resource toTool(McpToolCatalog t) {
        return Resource.builder().id(String.valueOf(t.getId())).type(ResourceType.TOOL).serverCode(t.getServerCode()).name(t.getToolName())
                .description(t.getDescription()).version(t.getVersion()).inputSchema(json(t.getInputSchema())).outputSchema(json(t.getOutputSchema()))
                .requiresApproval(Boolean.TRUE.equals(t.getRequiresApproval())).sensitivity(parseSensitivity(t.getSensitivity())).runMode(RunMode.SYNC).build();
    }

    private Resource toPrompt(McpPromptCatalog p) {
        return Resource.builder().id(String.valueOf(p.getId())).type(ResourceType.PROMPT).serverCode(p.getServerCode()).name(p.getPromptName())
                .description(p.getDescription()).version(p.getVersion()).argumentsSchema(json(p.getArgumentsSchema()))
                .requiresApproval(false).sensitivity(Sensitivity.LOW).runMode(RunMode.SYNC).build();
    }

    private Resource toResource(McpResourceCatalog r) {
        return Resource.builder().id(String.valueOf(r.getId())).type(ResourceType.RESOURCE).serverCode(r.getServerCode()).name(r.getName())
                .resourceUri(r.getResourceUri()).description(r.getDescription()).mimeType(r.getMimeType())
                .requiresApproval(false).sensitivity(Sensitivity.LOW).runMode(RunMode.SYNC).build();
    }

    private Resource toWorkflow(WorkflowTemplate w) {
        return Resource.builder().id(String.valueOf(w.getId())).type(ResourceType.WORKFLOW).serverCode(McpConstant.LOCAL_SERVER_CODE).name(w.getWorkflowName())
                .description(w.getDescription()).version(w.getVersion()).inputSchema(json(w.getInputSchema())).outputSchema(json(w.getOutputSchema()))
                .requiredCapabilities(w.getRequiredCapabilities()).thoughtChain(w.getThoughtChain()).toolSlots(toResourceSlots(w.getToolSlots()))
                .requiresApproval(false).sensitivity(Sensitivity.LOW).runMode(RunMode.SYNC).build();
    }

    private List<Resource> buildDomainResourceTemplates() {
        return List.of(
                Resource.builder()
                        .id("domain-resource:knowledge")
                        .type(ResourceType.RESOURCE)
                        .serverCode(McpConstant.LOCAL_SERVER_CODE)
                        .name("resource://knowledge/query")
                        .resourceUri("resource://knowledge/query")
                        .description("Knowledge domain MCP resource template")
                        .mimeType("application/json")
                        .requiresApproval(false)
                        .sensitivity(Sensitivity.LOW)
                        .runMode(RunMode.SYNC)
                        .build(),
                Resource.builder()
                        .id("domain-resource:user")
                        .type(ResourceType.RESOURCE)
                        .serverCode(McpConstant.LOCAL_SERVER_CODE)
                        .name("resource://user/preferences/current")
                        .resourceUri("resource://user/preferences/current")
                        .description("User preference MCP resource template")
                        .mimeType("application/json")
                        .requiresApproval(false)
                        .sensitivity(Sensitivity.LOW)
                        .runMode(RunMode.SYNC)
                        .build(),
                Resource.builder()
                        .id("domain-resource:memory")
                        .type(ResourceType.RESOURCE)
                        .serverCode(McpConstant.LOCAL_SERVER_CODE)
                        .name("resource://memory/session/current")
                        .resourceUri("resource://memory/session/current")
                        .description("Memory MCP resource template")
                        .mimeType("application/json")
                        .requiresApproval(false)
                        .sensitivity(Sensitivity.LOW)
                        .runMode(RunMode.SYNC)
                        .build(),
                Resource.builder()
                        .id("domain-resource:schedule")
                        .type(ResourceType.RESOURCE)
                        .serverCode(McpConstant.LOCAL_SERVER_CODE)
                        .name("resource://schedule/today")
                        .resourceUri("resource://schedule/today")
                        .description("Schedule MCP resource template")
                        .mimeType("application/json")
                        .requiresApproval(false)
                        .sensitivity(Sensitivity.LOW)
                        .runMode(RunMode.SYNC)
                        .build()
        );
    }

    private List<Resource.ToolSlotDto> toResourceSlots(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) return null;
        List<Resource.ToolSlotDto> out = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            out.add(Resource.ToolSlotDto.builder().slot(text(m.get("slot"))).capability(text(m.get("capability"))).required(bool(m.get("required"), true)).build());
        }
        return out;
    }

    private String embed(String name, String desc) {
        try {
            String v = llmClientUtil.getEmbedding((def(name, "") + " " + def(desc, "")).trim());
            return blank(v) || "[]".equals(v) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> jsonMap(String json) {
        if (blank(json)) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String json(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> stringList(Object o) {
        if (o == null) return new ArrayList<>();
        try {
            List<Object> list = objectMapper.convertValue(o, new TypeReference<>() {});
            List<String> out = new ArrayList<>();
            for (Object it : list) if (it != null) out.add(String.valueOf(it));
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private boolean blank(String s) { return s == null || s.isBlank(); }

    private String text(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private String def(String s, String d) { return blank(s) ? d : s.trim(); }

    private boolean contains(String s, String q) { return s != null && s.toLowerCase(Locale.ROOT).contains(q); }

    private boolean bool(Object o, boolean d) {
        if (o == null) return d;
        if (o instanceof Boolean b) return b;
        String t = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(t) || "1".equals(t) || "yes".equals(t)) return true;
        if ("false".equals(t) || "0".equals(t) || "no".equals(t)) return false;
        return d;
    }

    private Sensitivity parseSensitivity(String v) {
        if (blank(v)) return Sensitivity.LOW;
        try {
            return Sensitivity.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Sensitivity.LOW;
        }
    }
}


