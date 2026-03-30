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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * MCP 资源目录服务实现：
 * 统一维护工具、提示词、资源、工作流目录，并提供本地 JSON 同步能力。
 */
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
    public McpTool registerTool(McpTool tool) {
        if (tool == null || blank(tool.getName()) || blank(tool.getBeanName()) || blank(tool.getMethodName())) {
            throw new IllegalArgumentException("tool name/beanName/methodName required");
        }
        // 按 serverCode + toolName 幂等写入目录。
        McpToolCatalog row = toolCatalogMapper.selectOne(new LambdaQueryWrapper<McpToolCatalog>()
                .eq(McpToolCatalog::getServerCode, McpConstant.LOCAL_SERVER_CODE)
                .eq(McpToolCatalog::getToolName, tool.getName())
                .last("LIMIT 1"));
        if (row == null) {
            row = new McpToolCatalog();
            row.setServerCode(McpConstant.LOCAL_SERVER_CODE);
            row.setToolName(tool.getName());
            row.setEnabled(true);
        }
        row.setTitle(tool.getName());
        row.setDescription(tool.getDescription());
        row.setVersion(def(tool.getVersion(), "1.0.0"));
        row.setInputSchema(jsonMap(tool.getInputSchema()));
        row.setOutputSchema(jsonMap(tool.getOutputSchema()));
        row.setRequiresApproval(Boolean.TRUE.equals(tool.getRequiresApproval()));
        row.setSensitivity(tool.getSensitivity() == null ? "LOW" : tool.getSensitivity().name());
        row.setSyncedAt(LocalDateTime.now());
        // 嵌入向量用于后续资源检索召回。
        row.setEmbedding(embed(tool.getName(), tool.getDescription()));
        if (row.getId() == null) toolCatalogMapper.insert(row); else toolCatalogMapper.updateById(row);
        upsertImpl(tool.getName(), tool.getBeanName(), tool.getMethodName());
        tool.setId(row.getId());
        tool.setEmbedding(row.getEmbedding());
        return tool;
    }

    @Override
    public McpTool updateTool(McpTool tool) {
        if (tool == null || tool.getId() == null) throw new IllegalArgumentException("tool id required");
        McpToolCatalog row = toolCatalogMapper.selectById(tool.getId());
        if (row == null) throw new IllegalArgumentException("tool not found");
        if (!blank(tool.getName())) {
            row.setToolName(tool.getName().trim());
            row.setTitle(tool.getName().trim());
        }
        if (tool.getDescription() != null) row.setDescription(tool.getDescription());
        if (tool.getVersion() != null) row.setVersion(tool.getVersion());
        if (tool.getInputSchema() != null) row.setInputSchema(jsonMap(tool.getInputSchema()));
        if (tool.getOutputSchema() != null) row.setOutputSchema(jsonMap(tool.getOutputSchema()));
        if (tool.getRequiresApproval() != null) row.setRequiresApproval(tool.getRequiresApproval());
        if (tool.getSensitivity() != null) row.setSensitivity(tool.getSensitivity().name());
        row.setSyncedAt(LocalDateTime.now());
        row.setEmbedding(embed(row.getToolName(), row.getDescription()));
        toolCatalogMapper.updateById(row);
        if (!blank(tool.getBeanName()) && !blank(tool.getMethodName())) {
            upsertImpl(row.getToolName(), tool.getBeanName(), tool.getMethodName());
        }
        tool.setName(row.getToolName());
        tool.setEmbedding(row.getEmbedding());
        return tool;
    }

    @Override
    public void deleteTool(Long id) {
        if (id == null) return;
        McpToolCatalog row = toolCatalogMapper.selectById(id);
        if (row == null) return;
        toolCatalogMapper.deleteById(id);
        toolImplMappingMapper.delete(new LambdaQueryWrapper<McpToolImplMapping>()
                .eq(McpToolImplMapping::getServerCode, row.getServerCode())
                .eq(McpToolImplMapping::getToolName, row.getToolName()));
    }

    @Override
    public McpSkill registerSkill(McpSkill skill) {
        if (skill == null || blank(skill.getName())) throw new IllegalArgumentException("skill name required");
        if (workflowSkill(skill)) upsertWorkflow(skill); else upsertPrompt(skill);
        return skill;
    }

    @Override
    public McpSkill updateSkill(McpSkill skill) {
        if (skill == null || skill.getId() == null) throw new IllegalArgumentException("skill id required");
        if (workflowSkill(skill)) upsertWorkflow(skill); else upsertPrompt(skill);
        return skill;
    }

    @Override
    public void deleteSkill(Long id) {
        if (id == null) return;
        workflowTemplateMapper.deleteById(id);
        promptCatalogMapper.deleteById(id);
    }

    @Override
    public List<Resource> listAll() {
        // 聚合四类资源，统一投影为 Resource 返回给上层路由器。
        List<Resource> out = new ArrayList<>();
        toolCatalogMapper.selectList(null).forEach(t -> out.add(toTool(t)));
        promptCatalogMapper.selectList(null).forEach(p -> out.add(toPrompt(p)));
        resourceCatalogMapper.selectList(null).forEach(r -> out.add(toResource(r)));
        workflowTemplateMapper.selectList(null).forEach(w -> out.add(toWorkflow(w)));
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
    public Map<String, Object> syncCatalogFromJson() {
        int toolCount = 0;
        int workflowCount = 0;
        int promptCount = 0;
        List<String> warnings = new ArrayList<>();
        try {
            // 约定目录：json/tool 存工具定义，json/skill 存技能/工作流定义。
            Path toolDir = Path.of("json", "tool");
            Path skillDir = Path.of("json", "skill");
            if (Files.isDirectory(toolDir)) {
                try (Stream<Path> s = Files.list(toolDir)) {
                    for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(".json"))::iterator) {
                        Map<String, Object> m = readJsonFile(p);
                        if (m.isEmpty()) {
                            warnings.add("tool parse failed: " + p.getFileName());
                            continue;
                        }
                        syncToolFile(m);
                        toolCount++;
                    }
                }
            } else {
                warnings.add("missing: " + toolDir);
            }

            if (Files.isDirectory(skillDir)) {
                try (Stream<Path> s = Files.list(skillDir)) {
                    for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(".json"))::iterator) {
                        Map<String, Object> m = readJsonFile(p);
                        if (m.isEmpty()) {
                            warnings.add("skill parse failed: " + p.getFileName());
                            continue;
                        }
                        McpSkill skill = mapToSkill(m);
                        // ASYNC 或带能力编排信息的技能归类为工作流，其余落到 prompt 目录。
                        if (workflowSkill(skill)) {
                            upsertWorkflow(skill);
                            workflowCount++;
                        } else {
                            upsertPrompt(skill);
                            promptCount++;
                        }
                    }
                }
            } else {
                warnings.add("missing: " + skillDir);
            }
        } catch (Exception e) {
            log.error("syncCatalogFromJson failed", e);
            return Map.of("status", "error", "message", e.getMessage());
        }
        return Map.of("status", "success", "toolCount", toolCount, "workflowCount", workflowCount, "promptCount", promptCount, "warnings", warnings);
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

    private void syncToolFile(Map<String, Object> m) {
        McpTool tool = McpTool.builder()
                .name(text(m.get("name")))
                .description(text(m.get("description")))
                .version(def(text(m.get("version")), "1.0.0"))
                .owner(text(m.get("owner")))
                .beanName(text(m.get("beanName")))
                .methodName(text(m.get("methodName")))
                .inputSchema(json(m.get("inputSchema")))
                .outputSchema(json(m.get("outputSchema")))
                .requiresApproval(bool(m.get("requiresApproval"), false))
                .sensitivity(parseSensitivity(text(m.get("sensitivity"))))
                .build();
        registerTool(tool);
    }

    private McpSkill mapToSkill(Map<String, Object> m) {
        McpSkill s = new McpSkill();
        s.setName(text(m.get("name")));
        s.setDescription(text(m.get("description")));
        s.setVersion(def(text(m.get("version")), "1.0.0"));
        s.setOwner(text(m.get("owner")));
        s.setBeanName(text(m.get("beanName")));
        s.setMethodName(text(m.get("methodName")));
        s.setInputSchema(json(m.get("inputSchema")));
        s.setOutputSchema(json(m.get("outputSchema")));
        s.setRunMode(parseRunMode(text(m.get("runMode"))));
        s.setRequiredCapabilities(stringList(m.get("requiredCapabilities")));
        s.setThoughtChain(stringList(m.get("thoughtChain")));
        s.setToolSlots(toSkillSlots(m.get("toolSlots")));
        return s;
    }

    private void upsertWorkflow(McpSkill skill) {
        // 工作流模板保留能力声明、思维链、工具槽位等编排信息。
        WorkflowTemplate w = workflowTemplateMapper.selectOne(new LambdaQueryWrapper<WorkflowTemplate>()
                .eq(WorkflowTemplate::getWorkflowName, skill.getName()).last("LIMIT 1"));
        if (w == null) {
            w = new WorkflowTemplate();
            w.setWorkflowName(skill.getName());
            w.setEnabled(true);
        }
        w.setDescription(skill.getDescription());
        w.setInputSchema(jsonMap(skill.getInputSchema()));
        w.setOutputSchema(jsonMap(skill.getOutputSchema()));
        w.setRequiredCapabilities(skill.getRequiredCapabilities());
        w.setThoughtChain(skill.getThoughtChain());
        w.setToolSlots(toSlotMaps(skill.getToolSlots()));
        w.setVersion(def(skill.getVersion(), "1.0.0"));
        w.setEmbedding(embed(skill.getName(), skill.getDescription()));
        if (w.getId() == null) workflowTemplateMapper.insert(w); else workflowTemplateMapper.updateById(w);
        skill.setId(w.getId());
        skill.setEmbedding(w.getEmbedding());
    }

    private void upsertPrompt(McpSkill skill) {
        // 轻量技能以 Prompt 目录形态注册，兼容旧有提示词调用链路。
        McpPromptCatalog p = promptCatalogMapper.selectOne(new LambdaQueryWrapper<McpPromptCatalog>()
                .eq(McpPromptCatalog::getServerCode, McpConstant.LOCAL_SERVER_CODE)
                .eq(McpPromptCatalog::getPromptName, skill.getName())
                .last("LIMIT 1"));
        if (p == null) {
            p = new McpPromptCatalog();
            p.setServerCode(McpConstant.LOCAL_SERVER_CODE);
            p.setPromptName(skill.getName());
            p.setEnabled(true);
        }
        p.setTitle(skill.getName());
        p.setDescription(skill.getDescription());
        p.setArgumentsSchema(jsonMap(skill.getInputSchema()));
        p.setRawPayload(Map.of(
                "skillName", def(skill.getName(), ""),
                "legacyBeanName", def(skill.getBeanName(), ""),
                "legacyMethodName", def(skill.getMethodName(), ""),
                "runMode", (skill.getRunMode() == null ? RunMode.SYNC : skill.getRunMode()).name()
        ));
        p.setVersion(def(skill.getVersion(), "1.0.0"));
        p.setEmbedding(embed(skill.getName(), skill.getDescription()));
        p.setSyncedAt(LocalDateTime.now());
        if (p.getId() == null) promptCatalogMapper.insert(p); else promptCatalogMapper.updateById(p);
        skill.setId(p.getId());
        skill.setEmbedding(p.getEmbedding());
    }

    private void upsertImpl(String toolName, String beanName, String methodName) {
        McpToolImplMapping m = toolImplMappingMapper.findEnabledMapping(McpConstant.LOCAL_SERVER_CODE, toolName);
        if (m == null) {
            m = new McpToolImplMapping();
            m.setServerCode(McpConstant.LOCAL_SERVER_CODE);
            m.setToolName(toolName);
            m.setEnabled(true);
        }
        m.setImplType("SPRING_BEAN");
        m.setBeanName(beanName);
        m.setMethodName(methodName);
        m.setTimeoutMs(10000);
        if (m.getId() == null) toolImplMappingMapper.insert(m); else toolImplMappingMapper.updateById(m);
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

    private List<Resource.ToolSlotDto> toResourceSlots(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) return null;
        List<Resource.ToolSlotDto> out = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            out.add(Resource.ToolSlotDto.builder().slot(text(m.get("slot"))).capability(text(m.get("capability"))).required(bool(m.get("required"), true)).build());
        }
        return out;
    }

    private List<Map<String, Object>> toSlotMaps(List<McpSkill.ToolSlot> slots) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (slots == null) return out;
        for (McpSkill.ToolSlot s : slots) {
            out.add(Map.of("slot", def(s.getSlot(), ""), "capability", def(s.getCapability(), ""), "required", s.getRequired() == null || s.getRequired()));
        }
        return out;
    }

    private List<McpSkill.ToolSlot> toSkillSlots(Object o) {
        List<McpSkill.ToolSlot> out = new ArrayList<>();
        if (o == null) return out;
        try {
            List<Map<String, Object>> list = objectMapper.convertValue(o, new TypeReference<>() {});
            for (Map<String, Object> m : list) {
                out.add(McpSkill.ToolSlot.builder().slot(text(m.get("slot"))).capability(text(m.get("capability"))).required(bool(m.get("required"), true)).build());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private Map<String, Object> readJsonFile(Path p) {
        try {
            String s = Files.readString(p);
            return objectMapper.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String embed(String name, String desc) {
        try {
            // 失败时返回 null，不阻断目录同步主流程。
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

    private boolean workflowSkill(McpSkill s) {
        return s != null && (s.getRunMode() == RunMode.ASYNC || notEmpty(s.getRequiredCapabilities()) || notEmpty(s.getToolSlots()) || notEmpty(s.getThoughtChain()));
    }

    private boolean notEmpty(Collection<?> c) { return c != null && !c.isEmpty(); }

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

    private RunMode parseRunMode(String v) {
        if (blank(v)) return RunMode.SYNC;
        try {
            return RunMode.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return RunMode.SYNC;
        }
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
