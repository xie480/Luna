package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.McpSkill;
import org.yilena.luna.entity.McpTool;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.mapper.McpSkillMapper;
import org.yilena.luna.mapper.McpToolMapper;
import org.yilena.luna.service.McpService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class McpServiceImpl implements McpService {

    private final McpToolMapper toolMapper;
    private final McpSkillMapper skillMapper;

    @Override
    public McpTool registerTool(McpTool tool) {
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
        
        // 搜索 Tools
        LambdaQueryWrapper<McpTool> toolWrapper = new LambdaQueryWrapper<>();
        if (query != null && !query.isBlank()) {
            toolWrapper.like(McpTool::getName, query)
                    .or()
                    .like(McpTool::getDescription, query);
        }
        List<McpTool> tools = toolMapper.selectList(toolWrapper);
        resources.addAll(tools.stream().map(this::toResource).toList());

        // 搜索 Skills
        LambdaQueryWrapper<McpSkill> skillWrapper = new LambdaQueryWrapper<>();
        if (query != null && !query.isBlank()) {
            skillWrapper.like(McpSkill::getName, query)
                    .or()
                    .like(McpSkill::getDescription, query);
        }
        List<McpSkill> skills = skillMapper.selectList(skillWrapper);
        resources.addAll(skills.stream().map(this::toResource).toList());

        return resources;
    }

    // --- DTO 轉換輔助方法 ---

    private Resource toResource(McpTool tool) {
        return Resource.builder()
                .id(tool.getId())
                .type("TOOL")
                .name(tool.getName())
                .description(tool.getDescription())
                .version(tool.getVersion())
                .owner(tool.getOwner())
                .beanName(tool.getBeanName())
                .methodName(tool.getMethodName())
                .inputSchema(tool.getInputSchema())
                .outputSchema(tool.getOutputSchema())
                // Tool 默認屬性
                .runMode("SYNC")
                .requiresApproval(false)
                .sensitivity("LOW")
                .build();
    }

    private Resource toResource(McpSkill skill) {
        return Resource.builder()
                .id(skill.getId())
                .type("SKILL")
                .name(skill.getName())
                .description(skill.getDescription())
                .version(skill.getVersion())
                .owner(skill.getOwner())
                .beanName(skill.getBeanName())
                .methodName(skill.getMethodName())
                .inputSchema(skill.getInputSchema())
                .outputSchema(skill.getOutputSchema())
                // Skill 特有屬性
                .runMode(skill.getRunMode())
                .requiresApproval(skill.getRequiresApproval())
                .sensitivity(skill.getSensitivity())
                .build();
    }
}
