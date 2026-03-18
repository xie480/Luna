package org.yilena.luna.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.McpSkill;
import org.yilena.luna.entity.McpTool;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.enums.Sensitivity;
import org.yilena.luna.mapper.McpSkillMapper;
import org.yilena.luna.mapper.McpToolMapper;
import org.yilena.luna.service.McpService;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpServiceImpl implements McpService {

    private final McpToolMapper toolMapper;
    private final McpSkillMapper skillMapper;
    private final LlmClientUtil llmClientUtil;

    @Override
    public McpTool registerTool(McpTool tool) {
        // 註冊時自動生成向量
        generateToolEmbedding(tool);
        
        if (tool.getId() == null) {
            toolMapper.insert(tool);
        } else {
            McpTool exist = toolMapper.selectById(tool.getId());
            if (exist == null) {
                toolMapper.insert(tool);
            } else {
                toolMapper.updateById(tool);
            }
        }
        return tool;
    }

    @Override
    public McpSkill registerSkill(McpSkill skill) {
        // 註冊時自動生成向量
        generateSkillEmbedding(skill);
        
        if (skill.getId() == null) {
            skillMapper.insert(skill);
        } else {
            McpSkill exist = skillMapper.selectById(skill.getId());
            if (exist == null) {
                skillMapper.insert(skill);
            } else {
                skillMapper.updateById(skill);
            }
        }
        return skill;
    }

    private void generateToolEmbedding(McpTool tool) {
        try {
            // 將工具名稱和描述拼接作為語義特徵
            String text = tool.getName() + " " + (tool.getDescription() != null ? tool.getDescription() : "");
            String vector = llmClientUtil.getEmbedding(text);
            if (vector != null && !vector.trim().isEmpty() && !vector.trim().equals("[]")) {
                tool.setEmbedding(vector);
                log.info("成功生成 Tool 向量: {}", tool.getName());
            }
        } catch (Exception e) {
            log.error("生成 Tool 向量失敗: {}", tool.getName(), e);
        }
    }

    private void generateSkillEmbedding(McpSkill skill) {
        try {
            // 將技能名稱和描述拼接作為語義特徵
            String text = skill.getName() + " " + (skill.getDescription() != null ? skill.getDescription() : "");
            String vector = llmClientUtil.getEmbedding(text);
            if (vector != null && !vector.trim().isEmpty() && !vector.trim().equals("[]")) {
                skill.setEmbedding(vector);
                log.info("成功生成 Skill 向量: {}", skill.getName());
            }
        } catch (Exception e) {
            log.error("生成 Skill 向量失敗: {}", skill.getName(), e);
        }
    }

    @Override
    public List<Resource> listAll() {
        List<Resource> resources = new ArrayList<>();
        
        // 轉換 Tools
        List<McpTool> tools = toolMapper.selectList(null);
        resources.addAll(tools.stream().map(this::toResource).toList());

        // 轉換 Skills
        List<McpSkill> skills = skillMapper.selectList(null);
        resources.addAll(skills.stream().map(this::toResource).toList());

        return resources;
    }

    @Override
    public Resource getResourceById(String id) {
        // 先查 Tool
        McpTool tool = toolMapper.selectById(id);
        if (tool != null) {
            return toResource(tool);
        }
        // 再查 Skill
        McpSkill skill = skillMapper.selectById(id);
        if (skill != null) {
            return toResource(skill);
        }
        return null;
    }

    @Override
    public List<Resource> searchResources(String query) {
        List<Resource> resources = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return resources;
        }

        try {
            // 1. 將用戶的查詢語句向量化
            String queryVectorStr = llmClientUtil.getEmbedding(query);

            if (queryVectorStr != null && !queryVectorStr.trim().isEmpty() && !queryVectorStr.trim().equals("[]")) {
                // 2. 向量檢索 Tools (取 Top 5)
                List<McpTool> tools = toolMapper.searchByVector(queryVectorStr, 5);
                resources.addAll(tools.stream().map(this::toResource).toList());

                // 3. 向量檢索 Skills (取 Top 5)
                List<McpSkill> skills = skillMapper.searchByVector(queryVectorStr, 5);
                resources.addAll(skills.stream().map(this::toResource).toList());
                
                log.info("向量檢索完成，Query: [{}], 命中 Tool 數量: {}, 命中 Skill 數量: {}", query, tools.size(), skills.size());
            } else {
                log.warn("查詢語句向量化失敗，無法進行檢索: {}", query);
            }
        } catch (Exception e) {
            log.error("向量檢索資源異常: {}", e.getMessage(), e);
        }

        return resources;
    }

    // --- DTO 轉換輔助方法 ---

    private Resource toResource(McpTool tool) {
        return Resource.builder()
                .id(tool.getId())
                .type(ResourceType.TOOL)
                .name(tool.getName())
                .description(tool.getDescription())
                .version(tool.getVersion())
                .owner(tool.getOwner())
                .beanName(tool.getBeanName())
                .methodName(tool.getMethodName())
                .inputSchema(tool.getInputSchema())
                .outputSchema(tool.getOutputSchema())
                // Tool 默認屬性
                .runMode(RunMode.SYNC)
                .requiresApproval(false)
                .sensitivity(Sensitivity.LOW)
                .build();
    }

    private Resource toResource(McpSkill skill) {
        return Resource.builder()
                .id(skill.getId())
                .type(ResourceType.SKILL)
                .name(skill.getName())
                .description(skill.getDescription())
                .version(skill.getVersion())
                .owner(skill.getOwner())
                .beanName(skill.getBeanName())
                .methodName(skill.getMethodName())
                .inputSchema(skill.getInputSchema())
                .outputSchema(skill.getOutputSchema())
                // Skill 特有屬性
                .runMode(skill.getRunMode() != null ? skill.getRunMode() : RunMode.SYNC)
                .requiresApproval(skill.getRequiresApproval() != null ? skill.getRequiresApproval() : false)
                .sensitivity(skill.getSensitivity() != null ? skill.getSensitivity() : Sensitivity.LOW)
                .build();
    }
}
