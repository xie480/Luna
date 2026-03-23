package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpServiceImpl implements McpService {

    private final McpToolMapper toolMapper;
    private final McpSkillMapper skillMapper;
    private final LlmClientUtil llmClientUtil;
    private final ObjectMapper objectMapper;

    @Override
    public McpTool registerTool(McpTool tool) {
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
    public McpTool updateTool(McpTool tool) {
        if (tool.getId() == null) {
            throw new IllegalArgumentException("更新工具必須提供 ID");
        }

        McpTool existing = toolMapper.selectById(tool.getId());
        if (existing != null) {
            boolean needReEmbedding = false;
            if (tool.getName() != null && !tool.getName().equals(existing.getName())) {
                needReEmbedding = true;
            }
            if (tool.getDescription() != null && !tool.getDescription().equals(existing.getDescription())) {
                needReEmbedding = true;
            }

            if (needReEmbedding) {
                generateToolEmbedding(tool);
            } else {
                tool.setEmbedding(existing.getEmbedding());
            }
            toolMapper.updateById(tool);
            return toolMapper.selectById(tool.getId());
        } else {
            throw new IllegalArgumentException("未找到 ID 為 " + tool.getId() + " 的工具");
        }
    }

    @Override
    public void deleteTool(Long id) {
        toolMapper.deleteById(id);
    }

    @Override
    public McpSkill registerSkill(McpSkill skill) {
        normalizeSkillFields(skill);
        validateSkillDefinition(skill, true);

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

    @Override
    public McpSkill updateSkill(McpSkill skill) {
        if (skill.getId() == null) {
            throw new IllegalArgumentException("更新技能必須提供 ID");
        }

        normalizeSkillFields(skill);
        validateSkillDefinition(skill, false);

        McpSkill existing = skillMapper.selectById(skill.getId());
        if (existing != null) {
            boolean needReEmbedding = false;
            if (skill.getName() != null && !skill.getName().equals(existing.getName())) {
                needReEmbedding = true;
            }
            if (skill.getDescription() != null && !skill.getDescription().equals(existing.getDescription())) {
                needReEmbedding = true;
            }

            if (needReEmbedding) {
                generateSkillEmbedding(skill);
            } else {
                skill.setEmbedding(existing.getEmbedding());
            }
            skillMapper.updateById(skill);
            return skillMapper.selectById(skill.getId());
        } else {
            throw new IllegalArgumentException("未找到 ID 為 " + skill.getId() + " 的技能");
        }
    }

    @Override
    public void deleteSkill(Long id) {
        skillMapper.deleteById(id);
    }

    private void generateToolEmbedding(McpTool tool) {
        try {
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
        List<McpTool> tools = toolMapper.selectList(null);
        resources.addAll(tools.stream().map(this::toResource).toList());
        List<McpSkill> skills = skillMapper.selectList(null);
        resources.addAll(skills.stream().map(this::toResource).toList());
        return resources;
    }

    @Override
    public Resource getResourceById(Long id) {
        McpTool tool = toolMapper.selectById(id);
        if (tool != null) {
            return toResource(tool);
        }
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
            String queryVectorStr = llmClientUtil.getEmbedding(query);

            if (queryVectorStr != null && !queryVectorStr.trim().isEmpty() && !queryVectorStr.trim().equals("[]")) {
                List<McpTool> tools = toolMapper.searchByVector(queryVectorStr, 5);
                resources.addAll(tools.stream().map(this::toResource).toList());

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

    private Resource toResource(McpTool tool) {
        return Resource.builder()
                .id(String.valueOf(tool.getId()))
                .type(ResourceType.TOOL)
                .name(tool.getName())
                .description(tool.getDescription())
                .version(tool.getVersion())
                .owner(tool.getOwner())
                .beanName(tool.getBeanName())
                .methodName(tool.getMethodName())
                .inputSchema(tool.getInputSchema())
                .outputSchema(tool.getOutputSchema())
                .runMode(RunMode.SYNC)
                .requiresApproval(tool.getRequiresApproval() != null ? tool.getRequiresApproval() : false)
                .sensitivity(tool.getSensitivity() != null ? tool.getSensitivity() : Sensitivity.LOW)
                .requiredCapabilities(null)
                .toolSlots(null)
                .thoughtChain(null)
                .build();
    }

    private Resource toResource(McpSkill skill) {
        List<Resource.ToolSlotDto> slots = new ArrayList<>();
        if (skill.getToolSlots() != null) {
            for (Object rawSlot : skill.getToolSlots()) {
                McpSkill.ToolSlot slotObj = null;

                if (rawSlot instanceof McpSkill.ToolSlot ts) {
                    slotObj = ts;
                } else if (rawSlot instanceof Map<?, ?> mapSlot) {
                    try {
                        slotObj = objectMapper.convertValue(mapSlot, McpSkill.ToolSlot.class);
                    } catch (Exception e) {
                        log.warn("toolSlots 元素转换失败，skillId={}, skillName={}, rawSlot={}, err={}",
                                skill.getId(), skill.getName(), rawSlot, e.getMessage());
                    }
                } else if (rawSlot != null) {
                    log.warn("toolSlots 存在未知元素类型，skillId={}, skillName={}, type={}, value={}",
                            skill.getId(), skill.getName(), rawSlot.getClass().getName(), rawSlot);
                }

                if (slotObj != null) {
                    slots.add(Resource.ToolSlotDto.builder()
                            .slot(slotObj.getSlot())
                            .capability(slotObj.getCapability())
                            .required(slotObj.getRequired())
                            .build());
                }
            }
        }

        return Resource.builder()
                .id(String.valueOf(skill.getId()))
                .type(ResourceType.SKILL)
                .name(skill.getName())
                .description(skill.getDescription())
                .version(skill.getVersion())
                .owner(skill.getOwner())
                .beanName(skill.getBeanName())
                .methodName(skill.getMethodName())
                .inputSchema(skill.getInputSchema())
                .outputSchema(skill.getOutputSchema())
                .runMode(skill.getRunMode() != null ? skill.getRunMode() : RunMode.SYNC)
                .requiresApproval(false)
                .sensitivity(Sensitivity.LOW)
                .requiredCapabilities(skill.getRequiredCapabilities())
                .toolSlots(slots.isEmpty() ? null : slots)
                .thoughtChain(skill.getThoughtChain())
                .build();
    }

    private void normalizeSkillFields(McpSkill skill) {
        if (skill.getRunMode() == null) {
            skill.setRunMode(RunMode.SYNC);
        }
        if (skill.getRequiredCapabilities() == null) {
            skill.setRequiredCapabilities(new ArrayList<>());
        }
        if (skill.getToolSlots() == null) {
            skill.setToolSlots(new ArrayList<>());
        }
        if (skill.getThoughtChain() == null) {
            skill.setThoughtChain(new ArrayList<>());
        }
    }

    private void validateSkillDefinition(McpSkill skill, boolean isCreate) {
        if (skill == null) {
            throw new IllegalArgumentException("skill 不能为空");
        }
        if (isCreate && (skill.getName() == null || skill.getName().isBlank())) {
            throw new IllegalArgumentException("skillName 不能为空");
        }

        validateToolSlots(skill);
        validateCapabilityCoverage(skill);

        if (!skill.getThoughtChain().isEmpty() && skill.getThoughtChain().size() != skill.getToolSlots().size()) {
            throw new IllegalArgumentException("thoughtChain 长度必须与 toolSlots 一致");
        }
    }

    private void validateToolSlots(McpSkill skill) {
        if (skill.getToolSlots() == null || skill.getToolSlots().isEmpty()) {
            throw new IllegalArgumentException("toolSlots 不能为空，至少定义一个步骤槽位");
        }

        Set<String> slotNames = new HashSet<>();
        for (McpSkill.ToolSlot slot : skill.getToolSlots()) {
            if (slot == null) {
                throw new IllegalArgumentException("toolSlots 不能包含空元素");
            }
            if (slot.getSlot() == null || slot.getSlot().isBlank()) {
                throw new IllegalArgumentException("toolSlots.slot 不能为空");
            }
            if (slot.getCapability() == null || slot.getCapability().isBlank()) {
                throw new IllegalArgumentException("toolSlots.capability 不能为空");
            }
            if (!slotNames.add(slot.getSlot())) {
                throw new IllegalArgumentException("toolSlots.slot 不能重复: " + slot.getSlot());
            }
            if (slot.getRequired() == null) {
                slot.setRequired(Boolean.TRUE);
            }
        }
    }

    private void validateCapabilityCoverage(McpSkill skill) {
        Set<String> capabilitiesInSlots = skill.getToolSlots().stream()
                .map(McpSkill.ToolSlot::getCapability)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        if (skill.getRequiredCapabilities() != null && !skill.getRequiredCapabilities().isEmpty()) {
            for (String c : skill.getRequiredCapabilities()) {
                if (c == null || c.isBlank()) {
                    throw new IllegalArgumentException("requiredCapabilities 不能包含空值");
                }
                if (!capabilitiesInSlots.contains(c.trim())) {
                    throw new IllegalArgumentException("requiredCapabilities 中的能力未在 toolSlots 中声明: " + c);
                }
            }
        } else {
            skill.setRequiredCapabilities(new ArrayList<>(capabilitiesInSlots));
        }
    }
}
