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
 * McpServiceImpl ??
 */
public class McpServiceImpl implements McpService {

    private final McpToolCatalogMapper toolCatalogMapper; // 声明成员字段
    private final McpPromptCatalogMapper promptCatalogMapper; // 声明成员字段
    private final McpResourceCatalogMapper resourceCatalogMapper; // 声明成员字段
    private final McpToolImplMappingMapper toolImplMappingMapper; // 声明成员字段
    private final WorkflowTemplateMapper workflowTemplateMapper; // 声明成员字段
    private final McpServerRegistryMapper serverRegistryMapper; // 声明成员字段
    private final McpClientAdapter mcpClientAdapter; // 声明成员字段
    private final LlmClientUtil llmClientUtil; // 声明成员字段
    private final ObjectMapper objectMapper; // 声明成员字段

    @Override // 声明注解
    public McpTool registerTool(McpTool tool) { // 定义方法签名
        if (tool == null || blank(tool.getName()) || blank(tool.getBeanName()) || blank(tool.getMethodName())) { // 进行条件判断
            throw new IllegalArgumentException("tool name/beanName/methodName required"); // 抛出异常信息
        } // 结束当前代码块
        McpToolCatalog row = toolCatalogMapper.selectOne(new LambdaQueryWrapper<McpToolCatalog>() // 执行赋值操作
                .eq(McpToolCatalog::getServerCode, McpConstant.LOCAL_SERVER_CODE) // 执行当前逻辑
                .eq(McpToolCatalog::getToolName, tool.getName()) // 执行当前逻辑
                .last("LIMIT 1")); // 执行语句逻辑
        if (row == null) { // 进行条件判断
            row = new McpToolCatalog(); // 执行赋值操作
            row.setServerCode(McpConstant.LOCAL_SERVER_CODE); // 执行语句逻辑
            row.setToolName(tool.getName()); // 执行语句逻辑
            row.setEnabled(true); // 执行语句逻辑
        } // 结束当前代码块
        row.setTitle(tool.getName()); // 执行语句逻辑
        row.setDescription(tool.getDescription()); // 执行语句逻辑
        row.setVersion(def(tool.getVersion(), "1.0.0")); // 执行语句逻辑
        row.setInputSchema(jsonMap(tool.getInputSchema())); // 执行语句逻辑
        row.setOutputSchema(jsonMap(tool.getOutputSchema())); // 执行语句逻辑
        row.setRequiresApproval(Boolean.TRUE.equals(tool.getRequiresApproval())); // 执行语句逻辑
        row.setSensitivity(tool.getSensitivity() == null ? "LOW" : tool.getSensitivity().name()); // 执行赋值操作
        row.setSyncedAt(LocalDateTime.now()); // 执行语句逻辑
        row.setEmbedding(embed(tool.getName(), tool.getDescription())); // 执行语句逻辑
        if (row.getId() == null) toolCatalogMapper.insert(row); else toolCatalogMapper.updateById(row); // 进行条件判断
        upsertImpl(tool.getName(), tool.getBeanName(), tool.getMethodName()); // 执行语句逻辑
        tool.setId(row.getId()); // 执行语句逻辑
        tool.setEmbedding(row.getEmbedding()); // 执行语句逻辑
        return tool; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpTool updateTool(McpTool tool) { // 定义方法签名
        if (tool == null || tool.getId() == null) throw new IllegalArgumentException("tool id required"); // 进行条件判断
        McpToolCatalog row = toolCatalogMapper.selectById(tool.getId()); // 执行赋值操作
        if (row == null) throw new IllegalArgumentException("tool not found"); // 进行条件判断
        if (!blank(tool.getName())) { // 进行条件判断
            row.setToolName(tool.getName().trim()); // 执行语句逻辑
            row.setTitle(tool.getName().trim()); // 执行语句逻辑
        } // 结束当前代码块
        if (tool.getDescription() != null) row.setDescription(tool.getDescription()); // 进行条件判断
        if (tool.getVersion() != null) row.setVersion(tool.getVersion()); // 进行条件判断
        if (tool.getInputSchema() != null) row.setInputSchema(jsonMap(tool.getInputSchema())); // 进行条件判断
        if (tool.getOutputSchema() != null) row.setOutputSchema(jsonMap(tool.getOutputSchema())); // 进行条件判断
        if (tool.getRequiresApproval() != null) row.setRequiresApproval(tool.getRequiresApproval()); // 进行条件判断
        if (tool.getSensitivity() != null) row.setSensitivity(tool.getSensitivity().name()); // 进行条件判断
        row.setSyncedAt(LocalDateTime.now()); // 执行语句逻辑
        row.setEmbedding(embed(row.getToolName(), row.getDescription())); // 执行语句逻辑
        toolCatalogMapper.updateById(row); // 执行语句逻辑
        if (!blank(tool.getBeanName()) && !blank(tool.getMethodName())) { // 进行条件判断
            upsertImpl(row.getToolName(), tool.getBeanName(), tool.getMethodName()); // 执行语句逻辑
        } // 结束当前代码块
        tool.setName(row.getToolName()); // 执行语句逻辑
        tool.setEmbedding(row.getEmbedding()); // 执行语句逻辑
        return tool; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public void deleteTool(Long id) { // 定义方法签名
        if (id == null) return; // 进行条件判断
        McpToolCatalog row = toolCatalogMapper.selectById(id); // 执行赋值操作
        if (row == null) return; // 进行条件判断
        toolCatalogMapper.deleteById(id); // 执行语句逻辑
        toolImplMappingMapper.delete(new LambdaQueryWrapper<McpToolImplMapping>() // 执行当前逻辑
                .eq(McpToolImplMapping::getServerCode, row.getServerCode()) // 执行当前逻辑
                .eq(McpToolImplMapping::getToolName, row.getToolName())); // 执行语句逻辑
    } // 结束当前代码块

    @Override // 声明注解
    public McpSkill registerSkill(McpSkill skill) { // 定义方法签名
        if (skill == null || blank(skill.getName())) throw new IllegalArgumentException("skill name required"); // 进行条件判断
        if (workflowSkill(skill)) upsertWorkflow(skill); else upsertPrompt(skill); // 进行条件判断
        return skill; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpSkill updateSkill(McpSkill skill) { // 定义方法签名
        if (skill == null || skill.getId() == null) throw new IllegalArgumentException("skill id required"); // 进行条件判断
        if (workflowSkill(skill)) upsertWorkflow(skill); else upsertPrompt(skill); // 进行条件判断
        return skill; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public void deleteSkill(Long id) { // 定义方法签名
        if (id == null) return; // 进行条件判断
        workflowTemplateMapper.deleteById(id); // 执行语句逻辑
        promptCatalogMapper.deleteById(id); // 执行语句逻辑
    } // 结束当前代码块

    @Override // 声明注解
    public List<Resource> listAll() { // 定义方法签名
        List<Resource> out = new ArrayList<>(); // 执行赋值操作
        toolCatalogMapper.selectList(null).forEach(t -> out.add(toTool(t))); // 执行语句逻辑
        promptCatalogMapper.selectList(null).forEach(p -> out.add(toPrompt(p))); // 执行语句逻辑
        resourceCatalogMapper.selectList(null).forEach(r -> out.add(toResource(r))); // 执行语句逻辑
        workflowTemplateMapper.selectList(null).forEach(w -> out.add(toWorkflow(w))); // 执行语句逻辑
        return out; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public Resource getResourceById(Long id) { // 定义方法签名
        if (id == null) return null; // 进行条件判断
        McpToolCatalog t = toolCatalogMapper.selectById(id); // 执行赋值操作
        if (t != null) return toTool(t); // 进行条件判断
        McpPromptCatalog p = promptCatalogMapper.selectById(id); // 执行赋值操作
        if (p != null) return toPrompt(p); // 进行条件判断
        McpResourceCatalog r = resourceCatalogMapper.selectById(id); // 执行赋值操作
        if (r != null) return toResource(r); // 进行条件判断
        WorkflowTemplate w = workflowTemplateMapper.selectById(id); // 执行赋值操作
        if (w != null) return toWorkflow(w); // 进行条件判断
        return null; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public List<Resource> searchResources(String query) { // 定义方法签名
        if (blank(query)) return Collections.emptyList(); // 进行条件判断
        String q = query.toLowerCase(Locale.ROOT); // 执行赋值操作
        return listAll().stream() // 返回处理结果
                .filter(r -> contains(r.getName(), q) || contains(r.getDescription(), q) || contains(r.getResourceUri(), q)) // 执行当前逻辑
                .limit(20) // 执行当前逻辑
                .toList(); // 执行语句逻辑
    } // 结束当前代码块

    @Override // 声明注解
    public List<McpToolDescriptor> listTools(String serverCode) { // 定义方法签名
        return mcpClientAdapter.listTools(serverCode); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson) { // 定义方法签名
        return mcpClientAdapter.callTool(serverCode, toolName, argumentsJson); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public List<McpPromptDescriptor> listPrompts(String serverCode) { // 定义方法签名
        return mcpClientAdapter.listPrompts(serverCode); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson) { // 定义方法签名
        return mcpClientAdapter.getPrompt(serverCode, promptName, argumentsJson); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public List<McpResourceDescriptor> listResources(String serverCode) { // 定义方法签名
        return mcpClientAdapter.listResources(serverCode); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpResourceResult readResource(String serverCode, String resourceUri) { // 定义方法签名
        return mcpClientAdapter.readResource(serverCode, resourceUri); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public Map<String, Object> syncCatalogFromJson() { // 定义方法签名
        int toolCount = 0; // 执行赋值操作
        int workflowCount = 0; // 执行赋值操作
        int promptCount = 0; // 执行赋值操作
        List<String> warnings = new ArrayList<>(); // 执行赋值操作
        try { // 尝试执行核心逻辑
            Path toolDir = Path.of("json", "tool"); // 执行赋值操作
            Path skillDir = Path.of("json", "skill"); // 执行赋值操作
            if (Files.isDirectory(toolDir)) { // 进行条件判断
                try (Stream<Path> s = Files.list(toolDir)) { // 尝试执行核心逻辑
                    for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(".json"))::iterator) { // 执行循环处理
                        Map<String, Object> m = readJsonFile(p); // 执行赋值操作
                        if (m.isEmpty()) { // 进行条件判断
                            warnings.add("tool parse failed: " + p.getFileName()); // 执行语句逻辑
                            continue; // 执行语句逻辑
                        } // 结束当前代码块
                        syncToolFile(m); // 执行语句逻辑
                        toolCount++; // 执行语句逻辑
                    } // 结束当前代码块
                } // 结束当前代码块
            } else { // 切换到分支逻辑
                warnings.add("missing: " + toolDir); // 执行语句逻辑
            } // 结束当前代码块

            if (Files.isDirectory(skillDir)) { // 进行条件判断
                try (Stream<Path> s = Files.list(skillDir)) { // 尝试执行核心逻辑
                    for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(".json"))::iterator) { // 执行循环处理
                        Map<String, Object> m = readJsonFile(p); // 执行赋值操作
                        if (m.isEmpty()) { // 进行条件判断
                            warnings.add("skill parse failed: " + p.getFileName()); // 执行语句逻辑
                            continue; // 执行语句逻辑
                        } // 结束当前代码块
                        McpSkill skill = mapToSkill(m); // 执行赋值操作
                        if (workflowSkill(skill)) { // 进行条件判断
                            upsertWorkflow(skill); // 执行语句逻辑
                            workflowCount++; // 执行语句逻辑
                        } else { // 切换到分支逻辑
                            upsertPrompt(skill); // 执行语句逻辑
                            promptCount++; // 执行语句逻辑
                        } // 结束当前代码块
                    } // 结束当前代码块
                } // 结束当前代码块
            } else { // 切换到分支逻辑
                warnings.add("missing: " + skillDir); // 执行语句逻辑
            } // 结束当前代码块
        } catch (Exception e) { // 开始新的代码块
            log.error("syncCatalogFromJson failed", e); // 执行语句逻辑
            return Map.of("status", "error", "message", e.getMessage()); // 返回处理结果
        } // 结束当前代码块
        return Map.of("status", "success", "toolCount", toolCount, "workflowCount", workflowCount, "promptCount", promptCount, "warnings", warnings); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpServerRegistry upsertServerRegistry(McpServerRegistry registry) { // 定义方法签名
        if (registry == null || blank(registry.getServerCode())) { // 进行条件判断
            throw new IllegalArgumentException("serverCode required"); // 抛出异常信息
        } // 结束当前代码块
        McpServerRegistry exist = serverRegistryMapper.selectOne(new LambdaQueryWrapper<McpServerRegistry>() // 执行赋值操作
                .eq(McpServerRegistry::getServerCode, registry.getServerCode()) // 执行当前逻辑
                .last("LIMIT 1")); // 执行语句逻辑
        if (exist != null) registry.setId(exist.getId()); // 进行条件判断
        if (registry.getId() == null) serverRegistryMapper.insert(registry); // 进行条件判断
        else serverRegistryMapper.updateById(registry); // 处理其他分支
        return registry; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpToolCatalog upsertToolCatalog(McpToolCatalog toolCatalog) { // 定义方法签名
        if (toolCatalog == null || blank(toolCatalog.getServerCode()) || blank(toolCatalog.getToolName())) { // 进行条件判断
            throw new IllegalArgumentException("serverCode/toolName required"); // 抛出异常信息
        } // 结束当前代码块
        McpToolCatalog exist = toolCatalogMapper.selectOne(new LambdaQueryWrapper<McpToolCatalog>() // 执行赋值操作
                .eq(McpToolCatalog::getServerCode, toolCatalog.getServerCode()) // 执行当前逻辑
                .eq(McpToolCatalog::getToolName, toolCatalog.getToolName()) // 执行当前逻辑
                .last("LIMIT 1")); // 执行语句逻辑
        if (exist != null) toolCatalog.setId(exist.getId()); // 进行条件判断
        if (toolCatalog.getId() == null) toolCatalogMapper.insert(toolCatalog); // 进行条件判断
        else toolCatalogMapper.updateById(toolCatalog); // 处理其他分支
        return toolCatalog; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpToolImplMapping upsertToolImplMapping(McpToolImplMapping mapping) { // 定义方法签名
        if (mapping == null || blank(mapping.getServerCode()) || blank(mapping.getToolName())) { // 进行条件判断
            throw new IllegalArgumentException("serverCode/toolName required"); // 抛出异常信息
        } // 结束当前代码块
        McpToolImplMapping exist = toolImplMappingMapper.selectOne(new LambdaQueryWrapper<McpToolImplMapping>() // 执行赋值操作
                .eq(McpToolImplMapping::getServerCode, mapping.getServerCode()) // 执行当前逻辑
                .eq(McpToolImplMapping::getToolName, mapping.getToolName()) // 执行当前逻辑
                .last("LIMIT 1")); // 执行语句逻辑
        if (exist != null) mapping.setId(exist.getId()); // 进行条件判断
        if (mapping.getId() == null) toolImplMappingMapper.insert(mapping); // 进行条件判断
        else toolImplMappingMapper.updateById(mapping); // 处理其他分支
        return mapping; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpPromptCatalog upsertPromptCatalog(McpPromptCatalog promptCatalog) { // 定义方法签名
        if (promptCatalog == null || blank(promptCatalog.getServerCode()) || blank(promptCatalog.getPromptName())) { // 进行条件判断
            throw new IllegalArgumentException("serverCode/promptName required"); // 抛出异常信息
        } // 结束当前代码块
        McpPromptCatalog exist = promptCatalogMapper.selectOne(new LambdaQueryWrapper<McpPromptCatalog>() // 执行赋值操作
                .eq(McpPromptCatalog::getServerCode, promptCatalog.getServerCode()) // 执行当前逻辑
                .eq(McpPromptCatalog::getPromptName, promptCatalog.getPromptName()) // 执行当前逻辑
                .last("LIMIT 1")); // 执行语句逻辑
        if (exist != null) promptCatalog.setId(exist.getId()); // 进行条件判断
        if (promptCatalog.getId() == null) promptCatalogMapper.insert(promptCatalog); // 进行条件判断
        else promptCatalogMapper.updateById(promptCatalog); // 处理其他分支
        return promptCatalog; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpResourceCatalog upsertResourceCatalog(McpResourceCatalog resourceCatalog) { // 定义方法签名
        if (resourceCatalog == null || blank(resourceCatalog.getServerCode()) || blank(resourceCatalog.getResourceUri())) { // 进行条件判断
            throw new IllegalArgumentException("serverCode/resourceUri required"); // 抛出异常信息
        } // 结束当前代码块
        McpResourceCatalog exist = resourceCatalogMapper.selectOne(new LambdaQueryWrapper<McpResourceCatalog>() // 执行赋值操作
                .eq(McpResourceCatalog::getServerCode, resourceCatalog.getServerCode()) // 执行当前逻辑
                .eq(McpResourceCatalog::getResourceUri, resourceCatalog.getResourceUri()) // 执行当前逻辑
                .last("LIMIT 1")); // 执行语句逻辑
        if (exist != null) resourceCatalog.setId(exist.getId()); // 进行条件判断
        if (resourceCatalog.getId() == null) resourceCatalogMapper.insert(resourceCatalog); // 进行条件判断
        else resourceCatalogMapper.updateById(resourceCatalog); // 处理其他分支
        return resourceCatalog; // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public WorkflowTemplate upsertWorkflowTemplate(WorkflowTemplate workflowTemplate) { // 定义方法签名
        if (workflowTemplate == null || blank(workflowTemplate.getWorkflowName())) { // 进行条件判断
            throw new IllegalArgumentException("workflowName required"); // 抛出异常信息
        } // 结束当前代码块
        WorkflowTemplate exist = workflowTemplateMapper.selectOne(new LambdaQueryWrapper<WorkflowTemplate>() // 执行赋值操作
                .eq(WorkflowTemplate::getWorkflowName, workflowTemplate.getWorkflowName()) // 执行当前逻辑
                .last("LIMIT 1")); // 执行语句逻辑
        if (exist != null) workflowTemplate.setId(exist.getId()); // 进行条件判断
        if (workflowTemplate.getId() == null) workflowTemplateMapper.insert(workflowTemplate); // 进行条件判断
        else workflowTemplateMapper.updateById(workflowTemplate); // 处理其他分支
        return workflowTemplate; // 返回处理结果
    } // 结束当前代码块

    private void syncToolFile(Map<String, Object> m) { // 定义方法签名
        McpTool tool = McpTool.builder() // 执行赋值操作
                .name(text(m.get("name"))) // 执行当前逻辑
                .description(text(m.get("description"))) // 执行当前逻辑
                .version(def(text(m.get("version")), "1.0.0")) // 执行当前逻辑
                .owner(text(m.get("owner"))) // 执行当前逻辑
                .beanName(text(m.get("beanName"))) // 执行当前逻辑
                .methodName(text(m.get("methodName"))) // 执行当前逻辑
                .inputSchema(json(m.get("inputSchema"))) // 执行当前逻辑
                .outputSchema(json(m.get("outputSchema"))) // 执行当前逻辑
                .requiresApproval(bool(m.get("requiresApproval"), false)) // 执行当前逻辑
                .sensitivity(parseSensitivity(text(m.get("sensitivity")))) // 执行当前逻辑
                .build(); // 执行语句逻辑
        registerTool(tool); // 执行语句逻辑
    } // 结束当前代码块

    private McpSkill mapToSkill(Map<String, Object> m) { // 定义方法签名
        McpSkill s = new McpSkill(); // 执行赋值操作
        s.setName(text(m.get("name"))); // 执行语句逻辑
        s.setDescription(text(m.get("description"))); // 执行语句逻辑
        s.setVersion(def(text(m.get("version")), "1.0.0")); // 执行语句逻辑
        s.setOwner(text(m.get("owner"))); // 执行语句逻辑
        s.setBeanName(text(m.get("beanName"))); // 执行语句逻辑
        s.setMethodName(text(m.get("methodName"))); // 执行语句逻辑
        s.setInputSchema(json(m.get("inputSchema"))); // 执行语句逻辑
        s.setOutputSchema(json(m.get("outputSchema"))); // 执行语句逻辑
        s.setRunMode(parseRunMode(text(m.get("runMode")))); // 执行语句逻辑
        s.setRequiredCapabilities(stringList(m.get("requiredCapabilities"))); // 执行语句逻辑
        s.setThoughtChain(stringList(m.get("thoughtChain"))); // 执行语句逻辑
        s.setToolSlots(toSkillSlots(m.get("toolSlots"))); // 执行语句逻辑
        return s; // 返回处理结果
    } // 结束当前代码块

    private void upsertWorkflow(McpSkill skill) { // 定义方法签名
        WorkflowTemplate w = workflowTemplateMapper.selectOne(new LambdaQueryWrapper<WorkflowTemplate>() // 执行赋值操作
                .eq(WorkflowTemplate::getWorkflowName, skill.getName()).last("LIMIT 1")); // 执行语句逻辑
        if (w == null) { // 进行条件判断
            w = new WorkflowTemplate(); // 执行赋值操作
            w.setWorkflowName(skill.getName()); // 执行语句逻辑
            w.setEnabled(true); // 执行语句逻辑
        } // 结束当前代码块
        w.setDescription(skill.getDescription()); // 执行语句逻辑
        w.setInputSchema(jsonMap(skill.getInputSchema())); // 执行语句逻辑
        w.setOutputSchema(jsonMap(skill.getOutputSchema())); // 执行语句逻辑
        w.setRequiredCapabilities(skill.getRequiredCapabilities()); // 执行语句逻辑
        w.setThoughtChain(skill.getThoughtChain()); // 执行语句逻辑
        w.setToolSlots(toSlotMaps(skill.getToolSlots())); // 执行语句逻辑
        w.setVersion(def(skill.getVersion(), "1.0.0")); // 执行语句逻辑
        w.setEmbedding(embed(skill.getName(), skill.getDescription())); // 执行语句逻辑
        if (w.getId() == null) workflowTemplateMapper.insert(w); else workflowTemplateMapper.updateById(w); // 进行条件判断
        skill.setId(w.getId()); // 执行语句逻辑
        skill.setEmbedding(w.getEmbedding()); // 执行语句逻辑
    } // 结束当前代码块

    private void upsertPrompt(McpSkill skill) { // 定义方法签名
        McpPromptCatalog p = promptCatalogMapper.selectOne(new LambdaQueryWrapper<McpPromptCatalog>() // 执行赋值操作
                .eq(McpPromptCatalog::getServerCode, McpConstant.LOCAL_SERVER_CODE) // 执行当前逻辑
                .eq(McpPromptCatalog::getPromptName, skill.getName()) // 执行当前逻辑
                .last("LIMIT 1")); // 执行语句逻辑
        if (p == null) { // 进行条件判断
            p = new McpPromptCatalog(); // 执行赋值操作
            p.setServerCode(McpConstant.LOCAL_SERVER_CODE); // 执行语句逻辑
            p.setPromptName(skill.getName()); // 执行语句逻辑
            p.setEnabled(true); // 执行语句逻辑
        } // 结束当前代码块
        p.setTitle(skill.getName()); // 执行语句逻辑
        p.setDescription(skill.getDescription()); // 执行语句逻辑
        p.setArgumentsSchema(jsonMap(skill.getInputSchema())); // 执行语句逻辑
        p.setRawPayload(Map.of( // 执行当前逻辑
                "skillName", def(skill.getName(), ""), // 执行当前逻辑
                "legacyBeanName", def(skill.getBeanName(), ""), // 执行当前逻辑
                "legacyMethodName", def(skill.getMethodName(), ""), // 执行当前逻辑
                "runMode", (skill.getRunMode() == null ? RunMode.SYNC : skill.getRunMode()).name() // 执行赋值操作
        )); // 执行语句逻辑
        p.setVersion(def(skill.getVersion(), "1.0.0")); // 执行语句逻辑
        p.setEmbedding(embed(skill.getName(), skill.getDescription())); // 执行语句逻辑
        p.setSyncedAt(LocalDateTime.now()); // 执行语句逻辑
        if (p.getId() == null) promptCatalogMapper.insert(p); else promptCatalogMapper.updateById(p); // 进行条件判断
        skill.setId(p.getId()); // 执行语句逻辑
        skill.setEmbedding(p.getEmbedding()); // 执行语句逻辑
    } // 结束当前代码块

    private void upsertImpl(String toolName, String beanName, String methodName) { // 定义方法签名
        McpToolImplMapping m = toolImplMappingMapper.findEnabledMapping(McpConstant.LOCAL_SERVER_CODE, toolName); // 执行赋值操作
        if (m == null) { // 进行条件判断
            m = new McpToolImplMapping(); // 执行赋值操作
            m.setServerCode(McpConstant.LOCAL_SERVER_CODE); // 执行语句逻辑
            m.setToolName(toolName); // 执行语句逻辑
            m.setEnabled(true); // 执行语句逻辑
        } // 结束当前代码块
        m.setImplType("SPRING_BEAN"); // 执行语句逻辑
        m.setBeanName(beanName); // 执行语句逻辑
        m.setMethodName(methodName); // 执行语句逻辑
        m.setTimeoutMs(10000); // 执行语句逻辑
        if (m.getId() == null) toolImplMappingMapper.insert(m); else toolImplMappingMapper.updateById(m); // 进行条件判断
    } // 结束当前代码块

    private Resource toTool(McpToolCatalog t) { // 定义方法签名
        return Resource.builder().id(String.valueOf(t.getId())).type(ResourceType.TOOL).serverCode(t.getServerCode()).name(t.getToolName()) // 返回处理结果
                .description(t.getDescription()).version(t.getVersion()).inputSchema(json(t.getInputSchema())).outputSchema(json(t.getOutputSchema())) // 执行当前逻辑
                .requiresApproval(Boolean.TRUE.equals(t.getRequiresApproval())).sensitivity(parseSensitivity(t.getSensitivity())).runMode(RunMode.SYNC).build(); // 执行语句逻辑
    } // 结束当前代码块

    private Resource toPrompt(McpPromptCatalog p) { // 定义方法签名
        return Resource.builder().id(String.valueOf(p.getId())).type(ResourceType.PROMPT).serverCode(p.getServerCode()).name(p.getPromptName()) // 返回处理结果
                .description(p.getDescription()).version(p.getVersion()).argumentsSchema(json(p.getArgumentsSchema())) // 执行当前逻辑
                .requiresApproval(false).sensitivity(Sensitivity.LOW).runMode(RunMode.SYNC).build(); // 执行语句逻辑
    } // 结束当前代码块

    private Resource toResource(McpResourceCatalog r) { // 定义方法签名
        return Resource.builder().id(String.valueOf(r.getId())).type(ResourceType.RESOURCE).serverCode(r.getServerCode()).name(r.getName()) // 返回处理结果
                .resourceUri(r.getResourceUri()).description(r.getDescription()).mimeType(r.getMimeType()) // 执行当前逻辑
                .requiresApproval(false).sensitivity(Sensitivity.LOW).runMode(RunMode.SYNC).build(); // 执行语句逻辑
    } // 结束当前代码块

    private Resource toWorkflow(WorkflowTemplate w) { // 定义方法签名
        return Resource.builder().id(String.valueOf(w.getId())).type(ResourceType.WORKFLOW).serverCode(McpConstant.LOCAL_SERVER_CODE).name(w.getWorkflowName()) // 返回处理结果
                .description(w.getDescription()).version(w.getVersion()).inputSchema(json(w.getInputSchema())).outputSchema(json(w.getOutputSchema())) // 执行当前逻辑
                .requiredCapabilities(w.getRequiredCapabilities()).thoughtChain(w.getThoughtChain()).toolSlots(toResourceSlots(w.getToolSlots())) // 执行当前逻辑
                .requiresApproval(false).sensitivity(Sensitivity.LOW).runMode(RunMode.SYNC).build(); // 执行语句逻辑
    } // 结束当前代码块

    private List<Resource.ToolSlotDto> toResourceSlots(List<Map<String, Object>> maps) { // 定义方法签名
        if (maps == null || maps.isEmpty()) return null; // 进行条件判断
        List<Resource.ToolSlotDto> out = new ArrayList<>(); // 执行赋值操作
        for (Map<String, Object> m : maps) { // 执行循环处理
            out.add(Resource.ToolSlotDto.builder().slot(text(m.get("slot"))).capability(text(m.get("capability"))).required(bool(m.get("required"), true)).build()); // 执行语句逻辑
        } // 结束当前代码块
        return out; // 返回处理结果
    } // 结束当前代码块

    private List<Map<String, Object>> toSlotMaps(List<McpSkill.ToolSlot> slots) { // 定义方法签名
        List<Map<String, Object>> out = new ArrayList<>(); // 执行赋值操作
        if (slots == null) return out; // 进行条件判断
        for (McpSkill.ToolSlot s : slots) { // 执行循环处理
            out.add(Map.of("slot", def(s.getSlot(), ""), "capability", def(s.getCapability(), ""), "required", s.getRequired() == null || s.getRequired())); // 执行赋值操作
        } // 结束当前代码块
        return out; // 返回处理结果
    } // 结束当前代码块

    private List<McpSkill.ToolSlot> toSkillSlots(Object o) { // 定义方法签名
        List<McpSkill.ToolSlot> out = new ArrayList<>(); // 执行赋值操作
        if (o == null) return out; // 进行条件判断
        try { // 尝试执行核心逻辑
            List<Map<String, Object>> list = objectMapper.convertValue(o, new TypeReference<>() {}); // 执行赋值操作
            for (Map<String, Object> m : list) { // 执行循环处理
                out.add(McpSkill.ToolSlot.builder().slot(text(m.get("slot"))).capability(text(m.get("capability"))).required(bool(m.get("required"), true)).build()); // 执行语句逻辑
            } // 结束当前代码块
        } catch (Exception ignored) { // 开始新的代码块
        } // 结束当前代码块
        return out; // 返回处理结果
    } // 结束当前代码块

    private Map<String, Object> readJsonFile(Path p) { // 定义方法签名
        try { // 尝试执行核心逻辑
            String s = Files.readString(p); // 执行赋值操作
            return objectMapper.readValue(s, new TypeReference<>() {}); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return Collections.emptyMap(); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String embed(String name, String desc) { // 定义方法签名
        try { // 尝试执行核心逻辑
            String v = llmClientUtil.getEmbedding((def(name, "") + " " + def(desc, "")).trim()); // 执行赋值操作
            return blank(v) || "[]".equals(v) ? null : v; // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return null; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private Map<String, Object> jsonMap(String json) { // 定义方法签名
        if (blank(json)) return new LinkedHashMap<>(); // 进行条件判断
        try { // 尝试执行核心逻辑
            return objectMapper.readValue(json, new TypeReference<>() {}); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return new LinkedHashMap<>(); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String json(Object o) { // 定义方法签名
        if (o == null) return null; // 进行条件判断
        try { // 尝试执行核心逻辑
            return objectMapper.writeValueAsString(o); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return null; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private List<String> stringList(Object o) { // 定义方法签名
        if (o == null) return new ArrayList<>(); // 进行条件判断
        try { // 尝试执行核心逻辑
            List<Object> list = objectMapper.convertValue(o, new TypeReference<>() {}); // 执行赋值操作
            List<String> out = new ArrayList<>(); // 执行赋值操作
            for (Object it : list) if (it != null) out.add(String.valueOf(it)); // 执行循环处理
            return out; // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return new ArrayList<>(); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private boolean workflowSkill(McpSkill s) { // 定义方法签名
        return s != null && (s.getRunMode() == RunMode.ASYNC || notEmpty(s.getRequiredCapabilities()) || notEmpty(s.getToolSlots()) || notEmpty(s.getThoughtChain())); // 返回处理结果
    } // 结束当前代码块

    private boolean notEmpty(Collection<?> c) { return c != null && !c.isEmpty(); } // 定义方法签名

    private boolean blank(String s) { return s == null || s.isBlank(); } // 定义方法签名

    private String text(Object o) { return o == null ? "" : String.valueOf(o).trim(); } // 定义方法签名

    private String def(String s, String d) { return blank(s) ? d : s.trim(); } // 定义方法签名

    private boolean contains(String s, String q) { return s != null && s.toLowerCase(Locale.ROOT).contains(q); } // 定义方法签名

    private boolean bool(Object o, boolean d) { // 定义方法签名
        if (o == null) return d; // 进行条件判断
        if (o instanceof Boolean b) return b; // 进行条件判断
        String t = String.valueOf(o).trim().toLowerCase(Locale.ROOT); // 执行赋值操作
        if ("true".equals(t) || "1".equals(t) || "yes".equals(t)) return true; // 进行条件判断
        if ("false".equals(t) || "0".equals(t) || "no".equals(t)) return false; // 进行条件判断
        return d; // 返回处理结果
    } // 结束当前代码块

    private RunMode parseRunMode(String v) { // 定义方法签名
        if (blank(v)) return RunMode.SYNC; // 进行条件判断
        try { // 尝试执行核心逻辑
            return RunMode.valueOf(v.toUpperCase(Locale.ROOT)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return RunMode.SYNC; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private Sensitivity parseSensitivity(String v) { // 定义方法签名
        if (blank(v)) return Sensitivity.LOW; // 进行条件判断
        try { // 尝试执行核心逻辑
            return Sensitivity.valueOf(v.toUpperCase(Locale.ROOT)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return Sensitivity.LOW; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
