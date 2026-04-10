package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.McpClientAdapter;
import org.yilena.luna.constants.McpConstant;
import org.yilena.luna.entity.*;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.enums.Sensitivity;
import org.yilena.luna.mapper.*;
import org.yilena.luna.service.CapabilityCatalogSyncService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * MCP 服务实现类，负责目录读写、能力检索、协议代理调用以及目录向统一资源模型的转换。
 */
public class McpServiceImpl implements McpService {

    private final McpToolCatalogMapper toolCatalogMapper;
    private final McpPromptCatalogMapper promptCatalogMapper;
    private final McpResourceCatalogMapper resourceCatalogMapper;
    private final McpToolImplMappingMapper toolImplMappingMapper;
    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final McpServerRegistryMapper serverRegistryMapper;
    private final McpClientAdapter mcpClientAdapter;
    private final CapabilityCatalogSyncService capabilityCatalogSyncService;
    private final LlmClientUtil llmClientUtil;
    private final ObjectMapper objectMapper;

    @Value("${luna.mcp.execution.allow-spring-bean:false}")
    private boolean allowSpringBean;

    @Override
    /**
     * 汇总全部能力目录并转换为统一资源对象，供能力发现和检索使用。
     */
    public List<Resource> listAll() {
        /**
         * 依次加载工具、提示词、资源和工作流目录，再补充本地域资源模板，形成完整能力视图。
         */
        List<Resource> out = new ArrayList<>();
        toolCatalogMapper.selectList(null).forEach(t -> out.add(toTool(t)));
        promptCatalogMapper.selectList(null).forEach(p -> out.add(toPrompt(p)));
        resourceCatalogMapper.selectList(null).forEach(r -> out.add(toResource(r)));
        workflowTemplateMapper.selectList(null).forEach(w -> out.add(toWorkflow(w)));
        out.addAll(buildDomainResourceTemplates());
        return out;
    }

    @Override
    /**
     * 根据统一资源主键查询具体能力，并按目录类型转换为统一资源对象。
     */
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
    /**
     * 结合关键词匹配和语义检索搜索能力资源，提升目录发现效果。
     */
    public List<Resource> searchResources(String query) {
        if (blank(query)) return Collections.emptyList();
        String q = query.toLowerCase(Locale.ROOT);

        /**
         * 先做关键词过滤得到直接命中，再叠加语义检索结果，最后按资源唯一键去重合并。
         */
        List<Resource> keywordMatches = listAll().stream()
                .filter(r -> contains(r.getName(), q) || contains(r.getDescription(), q) || contains(r.getResourceUri(), q))
                .toList();
        List<Resource> semanticMatches = semanticSearchResources(query, 20);
        LinkedHashMap<String, Resource> merged = new LinkedHashMap<>();
        for (Resource resource : semanticMatches) {
            merged.put(resourceUniqueKey(resource), resource);
        }
        for (Resource resource : keywordMatches) {
            merged.putIfAbsent(resourceUniqueKey(resource), resource);
        }
        return merged.values().stream().limit(20).toList();
    }

    @Override
    /**
     * 透传调用底层 MCP 客户端查询工具目录。
     */
    public List<McpToolDescriptor> listTools(String serverCode) {
        return mcpClientAdapter.listTools(serverCode);
    }

    @Override
    /**
     * 透传调用底层 MCP 客户端执行工具。
     */
    public McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson) {
        return mcpClientAdapter.callTool(serverCode, toolName, argumentsJson);
    }

    @Override
    /**
     * 透传调用底层 MCP 客户端查询提示词目录。
     */
    public List<McpPromptDescriptor> listPrompts(String serverCode) {
        return mcpClientAdapter.listPrompts(serverCode);
    }

    @Override
    /**
     * 透传调用底层 MCP 客户端读取指定提示词模板。
     */
    public McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson) {
        return mcpClientAdapter.getPrompt(serverCode, promptName, argumentsJson);
    }

    @Override
    /**
     * 透传调用底层 MCP 客户端查询资源目录。
     */
    public List<McpResourceDescriptor> listResources(String serverCode) {
        return mcpClientAdapter.listResources(serverCode);
    }

    @Override
    /**
     * 透传调用底层 MCP 客户端读取资源内容。
     */
    public McpResourceResult readResource(String serverCode, String resourceUri) {
        return mcpClientAdapter.readResource(serverCode, resourceUri);
    }

    @Override
    /**
     * 触发能力目录同步任务，刷新本地缓存的 MCP 能力信息。
     */
    public Map<String, Object> syncCapabilityCatalog() {
        capabilityCatalogSyncService.syncFromServers();
        return Map.of("status", "success", "message", "capability catalog sync triggered");
    }

    @Override
    /**
     * 写入或更新 MCP 服务注册表记录。
     */
    public McpServerRegistry upsertServerRegistry(McpServerRegistry registry) {
        if (registry == null || blank(registry.getServerCode())) {
            throw new IllegalArgumentException("serverCode required");
        }

        /**
         * 先按服务编码定位既有记录，存在则更新，不存在则新增，保证 serverCode 维度唯一。
         */
        McpServerRegistry exist = serverRegistryMapper.selectOne(new LambdaQueryWrapper<McpServerRegistry>()
                .eq(McpServerRegistry::getServerCode, registry.getServerCode())
                .last("LIMIT 1"));
        if (exist != null) registry.setId(exist.getId());
        if (registry.getId() == null) serverRegistryMapper.insert(registry);
        else serverRegistryMapper.updateById(registry);
        return registry;
    }

    @Override
    /**
     * 写入或更新工具目录记录，并自动补充执行模式与语义向量。
     */
    public McpToolCatalog upsertToolCatalog(McpToolCatalog toolCatalog) {
        if (toolCatalog == null || blank(toolCatalog.getServerCode()) || blank(toolCatalog.getToolName())) {
            throw new IllegalArgumentException("serverCode/toolName required");
        }

        /**
         * 先规范执行模式并补充向量信息，再按服务编码和工具名判断新增还是更新。
         */
        toolCatalog.setExecutionMode(normalizeExecutionMode(toolCatalog.getExecutionMode()));
        enrichToolCatalog(toolCatalog);
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
    /**
     * 写入或更新工具实现映射，并校验实现类型和启用条件。
     */
    public McpToolImplMapping upsertToolImplMapping(McpToolImplMapping mapping) {
        if (mapping == null || blank(mapping.getServerCode()) || blank(mapping.getToolName())) {
            throw new IllegalArgumentException("serverCode/toolName required");
        }

        /**
         * 先规范实现类型和执行模式，再校验高风险的 Spring Bean 映射是否允许启用。
         */
        String implType = normalizeImplType(mapping.getImplType());
        mapping.setImplType(implType);
        mapping.setExecutionMode(normalizeExecutionMode(mapping.getExecutionMode()));
        if ("SPRING_BEAN".equals(implType) && Boolean.TRUE.equals(mapping.getEnabled()) && !allowSpringBean) {
            throw new IllegalArgumentException("SPRING_BEAN mapping cannot be enabled when luna.mcp.execution.allow-spring-bean=false");
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
    /**
     * 写入或更新提示词目录记录，并补充语义向量。
     */
    public McpPromptCatalog upsertPromptCatalog(McpPromptCatalog promptCatalog) {
        if (promptCatalog == null || blank(promptCatalog.getServerCode()) || blank(promptCatalog.getPromptName())) {
            throw new IllegalArgumentException("serverCode/promptName required");
        }
        enrichPromptCatalog(promptCatalog);
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
    /**
     * 写入或更新资源目录记录，并补充语义向量。
     */
    public McpResourceCatalog upsertResourceCatalog(McpResourceCatalog resourceCatalog) {
        if (resourceCatalog == null || blank(resourceCatalog.getServerCode()) || blank(resourceCatalog.getResourceUri())) {
            throw new IllegalArgumentException("serverCode/resourceUri required");
        }
        enrichResourceCatalog(resourceCatalog);
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
    /**
     * 写入或更新工作流模板，并补充语义向量。
     */
    public WorkflowTemplate upsertWorkflowTemplate(WorkflowTemplate workflowTemplate) {
        if (workflowTemplate == null || blank(workflowTemplate.getWorkflowName())) {
            throw new IllegalArgumentException("workflowName required");
        }
        enrichWorkflowTemplate(workflowTemplate);
        WorkflowTemplate exist = workflowTemplateMapper.selectOne(new LambdaQueryWrapper<WorkflowTemplate>()
                .eq(WorkflowTemplate::getWorkflowName, workflowTemplate.getWorkflowName())
                .last("LIMIT 1"));
        if (exist != null) workflowTemplate.setId(exist.getId());
        if (workflowTemplate.getId() == null) workflowTemplateMapper.insert(workflowTemplate);
        else workflowTemplateMapper.updateById(workflowTemplate);
        return workflowTemplate;
    }

    /**
     * 将工具目录实体转换为统一资源对象，便于上层按统一模型处理能力。
     */
    private Resource toTool(McpToolCatalog t) {
        return Resource.builder().id(String.valueOf(t.getId())).type(ResourceType.TOOL).serverCode(t.getServerCode()).name(t.getToolName())
                .description(t.getDescription()).version(t.getVersion()).inputSchema(json(t.getInputSchema())).outputSchema(json(t.getOutputSchema()))
                .executionMode(normalizeExecutionMode(t.getExecutionMode()))
                .requiresApproval(Boolean.TRUE.equals(t.getRequiresApproval())).sensitivity(parseSensitivity(t.getSensitivity())).runMode(RunMode.SYNC).build();
    }

    /**
     * 将提示词目录实体转换为统一资源对象。
     */
    private Resource toPrompt(McpPromptCatalog p) {
        return Resource.builder().id(String.valueOf(p.getId())).type(ResourceType.PROMPT).serverCode(p.getServerCode()).name(p.getPromptName())
                .description(p.getDescription()).version(p.getVersion()).argumentsSchema(json(p.getArgumentsSchema()))
                .requiresApproval(false).sensitivity(Sensitivity.LOW).runMode(RunMode.SYNC).build();
    }

    /**
     * 将资源目录实体转换为统一资源对象。
     */
    private Resource toResource(McpResourceCatalog r) {
        return Resource.builder().id(String.valueOf(r.getId())).type(ResourceType.RESOURCE).serverCode(r.getServerCode()).name(r.getName())
                .resourceUri(r.getResourceUri()).description(r.getDescription()).mimeType(r.getMimeType())
                .requiresApproval(false).sensitivity(Sensitivity.LOW).runMode(RunMode.SYNC).build();
    }

    /**
     * 将工作流模板实体转换为统一资源对象。
     */
    private Resource toWorkflow(WorkflowTemplate w) {
        return Resource.builder().id(String.valueOf(w.getId())).type(ResourceType.WORKFLOW).serverCode(McpConstant.LOCAL_SERVER_CODE).name(w.getWorkflowName())
                .description(w.getDescription()).version(w.getVersion()).inputSchema(json(w.getInputSchema())).outputSchema(json(w.getOutputSchema()))
                .requiredCapabilities(w.getRequiredCapabilities()).thoughtChain(w.getThoughtChain()).toolSlots(toResourceSlots(w.getToolSlots()))
                .requiresApproval(false).sensitivity(Sensitivity.LOW).runMode(RunMode.SYNC).build();
    }

    /**
     * 构建本地领域资源模板，补齐未落库但需要参与能力发现的内置资源。
     */
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

    /**
     * 将工作流中的插槽定义 Map 转换为统一的插槽 DTO 列表。
     */
    private List<Resource.ToolSlotDto> toResourceSlots(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) return null;
        List<Resource.ToolSlotDto> out = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            out.add(Resource.ToolSlotDto.builder().slot(text(m.get("slot"))).capability(text(m.get("capability"))).required(bool(m.get("required"), true)).build());
        }
        return out;
    }

    /**
     * 为目录项生成语义向量，供后续语义检索使用。
     */
    private String embed(String name, String desc) {
        try {
            String v = llmClientUtil.getEmbedding((def(name, "") + " " + def(desc, "")).trim());
            return blank(v) || "[]".equals(v) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 为工具目录补充语义向量。
     */
    private void enrichToolCatalog(McpToolCatalog toolCatalog) {
        if (toolCatalog == null) {
            return;
        }
        String text = def(toolCatalog.getToolName(), "") + " " + def(toolCatalog.getTitle(), "") + " " + def(toolCatalog.getDescription(), "");
        String generated = embed(text, toolCatalog.getDescription());
        if (!blank(generated)) {
            toolCatalog.setEmbedding(generated);
        }
    }

    /**
     * 为提示词目录补充语义向量。
     */
    private void enrichPromptCatalog(McpPromptCatalog promptCatalog) {
        if (promptCatalog == null) {
            return;
        }
        String text = def(promptCatalog.getPromptName(), "") + " " + def(promptCatalog.getTitle(), "") + " " + def(promptCatalog.getDescription(), "");
        String generated = embed(text, promptCatalog.getDescription());
        if (!blank(generated)) {
            promptCatalog.setEmbedding(generated);
        }
    }

    /**
     * 为资源目录补充语义向量。
     */
    private void enrichResourceCatalog(McpResourceCatalog resourceCatalog) {
        if (resourceCatalog == null) {
            return;
        }
        String text = def(resourceCatalog.getResourceUri(), "") + " " + def(resourceCatalog.getName(), "") + " " + def(resourceCatalog.getDescription(), "");
        String generated = embed(text, resourceCatalog.getDescription());
        if (!blank(generated)) {
            resourceCatalog.setEmbedding(generated);
        }
    }

    /**
     * 为工作流模板补充语义向量。
     */
    private void enrichWorkflowTemplate(WorkflowTemplate workflowTemplate) {
        if (workflowTemplate == null) {
            return;
        }
        String text = def(workflowTemplate.getWorkflowName(), "") + " " + def(workflowTemplate.getDescription(), "");
        String generated = embed(text, workflowTemplate.getDescription());
        if (!blank(generated)) {
            workflowTemplate.setEmbedding(generated);
        }
    }

    /**
     * 使用语义向量在多张目录表中执行检索，并统一合并去重结果。
     */
    private List<Resource> semanticSearchResources(String query, int limit) {
        String vector = embed(query, query);
        if (blank(vector)) {
            return Collections.emptyList();
        }
        int topK = Math.max(4, Math.min(limit, 20));
        LinkedHashMap<String, Resource> merged = new LinkedHashMap<>();

        /**
         * 分别在工具、提示词、资源和工作流目录中执行向量检索，单表失败时不影响整体结果。
         */
        try {
            toolCatalogMapper.searchByVector(vector, topK).stream()
                    .map(this::toTool)
                    .forEach(r -> merged.put(resourceUniqueKey(r), r));
        } catch (Exception e) {
            log.debug("tool vector search failed: {}", e.getMessage());
        }
        try {
            promptCatalogMapper.searchByVector(vector, topK).stream()
                    .map(this::toPrompt)
                    .forEach(r -> merged.put(resourceUniqueKey(r), r));
        } catch (Exception e) {
            log.debug("prompt vector search failed: {}", e.getMessage());
        }
        try {
            resourceCatalogMapper.searchByVector(vector, topK).stream()
                    .map(this::toResource)
                    .forEach(r -> merged.put(resourceUniqueKey(r), r));
        } catch (Exception e) {
            log.debug("resource vector search failed: {}", e.getMessage());
        }
        try {
            workflowTemplateMapper.searchByVector(vector, topK).stream()
                    .map(this::toWorkflow)
                    .forEach(r -> merged.put(resourceUniqueKey(r), r));
        } catch (Exception e) {
            log.debug("workflow vector search failed: {}", e.getMessage());
        }
        return merged.values().stream().limit(limit).toList();
    }

    /**
     * 生成能力资源的唯一去重键，避免多渠道结果合并后重复。
     */
    private String resourceUniqueKey(Resource resource) {
        if (resource == null) {
            return "";
        }
        return def(resource.getType() == null ? "" : resource.getType().name(), "")
                + "|" + def(resource.getServerCode(), "")
                + "|" + def(resource.getName(), "")
                + "|" + def(resource.getResourceUri(), "");
    }

    /**
     * 将 JSON 文本安全解析为 Map，解析失败时返回空对象。
     */
    private Map<String, Object> jsonMap(String json) {
        if (blank(json)) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 将对象序列化为 JSON 文本，失败时返回空值。
     */
    private String json(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将任意列表对象安全转换为字符串列表。
     */
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

    /**
     * 判断字符串是否为空白。
     */
    private boolean blank(String s) { return s == null || s.isBlank(); }

    /**
     * 规范化实现类型，限制到系统支持的枚举值。
     */
    private String normalizeImplType(String implType) {
        if (blank(implType)) {
            return "LOCAL_HANDLER";
        }
        String normalized = implType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOCAL_HANDLER", "HTTP", "RPC", "WORKFLOW", "SPRING_BEAN" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported implType: " + normalized);
        };
    }

    /**
     * 规范化执行模式，非法值回退到默认 MCP 模式。
     */
    private String normalizeExecutionMode(String executionMode) {
        if (blank(executionMode)) {
            return "MCP";
        }
        String normalized = executionMode.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MCP", "LEGACY" -> normalized;
            default -> "MCP";
        };
    }

    /**
     * 将任意对象安全转换为字符串。
     */
    private String text(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    /**
     * 为可能为空的字符串提供默认值。
     */
    private String def(String s, String d) { return blank(s) ? d : s.trim(); }

    /**
     * 判断字符串是否包含指定关键词，忽略大小写前置处理由调用方完成。
     */
    private boolean contains(String s, String q) { return s != null && s.toLowerCase(Locale.ROOT).contains(q); }

    /**
     * 将多种布尔表达形式统一转换为布尔值。
     */
    private boolean bool(Object o, boolean d) {
        if (o == null) return d;
        if (o instanceof Boolean b) return b;
        String t = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(t) || "1".equals(t) || "yes".equals(t)) return true;
        if ("false".equals(t) || "0".equals(t) || "no".equals(t)) return false;
        return d;
    }

    /**
     * 将字符串安全转换为敏感等级，无法识别时回退为低敏感。
     */
    private Sensitivity parseSensitivity(String v) {
        if (blank(v)) return Sensitivity.LOW;
        try {
            return Sensitivity.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Sensitivity.LOW;
        }
    }
}
