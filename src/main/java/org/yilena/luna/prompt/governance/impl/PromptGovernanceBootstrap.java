package org.yilena.luna.prompt.governance.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;
import org.yilena.luna.prompt.governance.mapper.PromptCategoryMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptGovernanceBootstrap implements ApplicationRunner {

    private final PromptCategoryMapper promptCategoryMapper;
    private final PromptItemMapper promptItemMapper;
    private final PromptItemVersionMapper promptItemVersionMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedCategories();
            seedBuiltinPrompts();
        } catch (Exception ex) {
            log.debug("prompt governance bootstrap skipped: {}", ex.getMessage());
        }
    }

    private void seedCategories() {
        for (CategorySeed row : categorySeeds()) {
            PromptCategoryEntity existing = promptCategoryMapper.selectOne(
                    new LambdaQueryWrapper<PromptCategoryEntity>()
                            .eq(PromptCategoryEntity::getCategoryKey, row.categoryKey())
                            .last("limit 1")
            );
            if (existing != null) {
                continue;
            }
            PromptCategoryEntity entity = PromptCategoryEntity.builder()
                    .categoryKey(row.categoryKey())
                    .categoryName(row.categoryName())
                    .parentCategoryKey("")
                    .description(row.description())
                    .sortOrder(row.sortOrder())
                    .keywordMatchAllowed(row.keywordMatchAllowed())
                    .isExecutionCategory(row.executionCategory())
                    .enabled(true)
                    .build();
            promptCategoryMapper.insert(entity);
        }
    }

    private void seedBuiltinPrompts() {
        Map<String, PromptItemRecord> builtins = new LinkedHashMap<>(BuiltinPromptCatalog.all());
        for (Map.Entry<String, String> entry : reflectAgentPromptTemplates().entrySet()) {
            PromptItemRecord base = builtins.get(entry.getKey());
            if (base == null || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            builtins.put(entry.getKey(), PromptItemRecord.builder()
                    .itemId(base.getItemId())
                    .versionId(base.getVersionId())
                    .key(base.getKey())
                    .name(base.getName())
                    .value(entry.getValue())
                    .category(base.getCategory())
                    .subCategory(base.getSubCategory())
                    .description(base.getDescription())
                    .runtimeSlot(base.getRuntimeSlot())
                    .hasTemplateVariables(base.isHasTemplateVariables())
                    .templateVariables(base.getTemplateVariables())
                    .keywordMatchEnabled(base.isKeywordMatchEnabled())
                    .matchKeywords(base.getMatchKeywords())
                    .assemblyMode(base.getAssemblyMode())
                    .matchScope(defaultScope(base.getKey(), base.getMatchScope()))
                    .editPolicy(base.getEditPolicy())
                    .enabled(base.isEnabled())
                    .priority(base.getPriority())
                    .status(base.getStatus())
                    .version(base.getVersion())
                    .versionLabel(base.getVersionLabel())
                    .changeNote(base.getChangeNote())
                    .build());
        }

        for (PromptItemRecord row : builtins.values()) {
            upsertPrompt(row);
        }
    }

    private void upsertPrompt(PromptItemRecord row) {
        PromptItemEntity item = promptItemMapper.selectOne(
                new LambdaQueryWrapper<PromptItemEntity>()
                        .eq(PromptItemEntity::getPromptKey, row.getKey())
                        .last("limit 1")
        );
        if (item == null) {
            item = PromptItemEntity.builder()
                    .category(row.getCategory())
                    .subCategory(row.getSubCategory())
                    .promptKey(row.getKey())
                    .promptName(row.getName())
                    .runtimeSlot(row.getRuntimeSlot())
                    .hasTemplateVariables(row.isHasTemplateVariables())
                    .keywordMatchEnabled(row.isKeywordMatchEnabled())
                    .assemblyMode(row.getAssemblyMode())
                    .enabled(row.isEnabled())
                    .priority(row.getPriority() == null ? 80 : row.getPriority())
                    .status("active")
                    .isBuiltin(true)
                    .description(row.getDescription())
                    .build();
            promptItemMapper.insert(item);
        }

        PromptItemVersionEntity activeVersion = findActiveVersion(item.getId());
        if (activeVersion == null) {
            PromptItemVersionEntity version = PromptItemVersionEntity.builder()
                    .promptItemId(item.getId())
                    .versionNo(row.getVersion() == null || row.getVersion().isBlank() ? "1.0.0" : row.getVersion())
                    .versionLabel(row.getVersionLabel() == null || row.getVersionLabel().isBlank() ? "1.0.0" : row.getVersionLabel())
                    .promptValue(row.getValue() == null ? "" : row.getValue())
                    .templateVariables(row.getTemplateVariables() == null ? List.of() : row.getTemplateVariables())
                    .matchKeywords(row.getMatchKeywords() == null ? List.of() : row.getMatchKeywords())
                    .matchScope(defaultScope(row.getKey(), row.getMatchScope()))
                    .editPolicy(row.getEditPolicy() == null ? Map.of("create", true, "update", true, "delete", true) : row.getEditPolicy())
                    .status("active")
                    .changeNote("bootstrap_seed")
                    .isActive(true)
                    .build();
            promptItemVersionMapper.insert(version);
            promptItemMapper.update(null, new LambdaUpdateWrapper<PromptItemEntity>()
                    .eq(PromptItemEntity::getId, item.getId())
                    .set(PromptItemEntity::getCurrentVersionId, version.getId())
                    .set(PromptItemEntity::getStatus, "active")
                    .set(PromptItemEntity::getEnabled, true)
                    .set(PromptItemEntity::getIsBuiltin, true));
        }
    }

    private PromptItemVersionEntity findActiveVersion(Long itemId) {
        List<PromptItemVersionEntity> rows = promptItemVersionMapper.selectList(
                new LambdaQueryWrapper<PromptItemVersionEntity>()
                        .eq(PromptItemVersionEntity::getPromptItemId, itemId)
                        .eq(PromptItemVersionEntity::getIsActive, true)
                        .orderByDesc(PromptItemVersionEntity::getCreatedAt)
                        .last("limit 1")
        );
        if (rows != null && !rows.isEmpty()) {
            return rows.get(0);
        }
        return null;
    }

    private Map<String, String> reflectAgentPromptTemplates() {
        Map<String, String> out = new LinkedHashMap<>();
        putField(out, "agent.reconstruction.default_v1", "org.yilena.luna.context.impl.DefaultInputReconstructionAgent", "RECONSTRUCTION_PROMPT");
        putField(out, "agent.rerank.default_v1", "org.yilena.luna.context.impl.DefaultGlobalContextRerankAgent", "GLOBAL_RERANK_PROMPT");
        putField(out, "agent.recovery.default_v1", "org.yilena.luna.context.impl.DefaultRecoveryContextAgent", "RECOVERY_DECISION_PROMPT");
        putField(out, "agent.tool_semantic.default_v1", "org.yilena.luna.context.impl.DefaultToolSemanticAgent", "TOOL_SEMANTIC_PROMPT");
        putField(out, "agent.summary.default_v1", "org.yilena.luna.context.impl.DefaultSummaryAgent", "SUMMARY_PROMPT");
        return out;
    }

    private void putField(Map<String, String> out, String key, String className, String fieldName) {
        try {
            Class<?> type = Class.forName(className);
            Field field = type.getDeclaredField(fieldName);
            if (!Modifier.isStatic(field.getModifiers())) {
                return;
            }
            field.setAccessible(true);
            Object raw = field.get(null);
            if (raw != null) {
                String value = String.valueOf(raw);
                if (!value.isBlank()) {
                    out.put(key, value);
                }
            }
        } catch (Exception ignore) {
            // optional seed
        }
    }

    private Map<String, Object> defaultScope(String key, Map<String, Object> origin) {
        if (origin != null && !origin.isEmpty()) {
            return origin;
        }
        if (key == null || key.isBlank()) {
            return Map.of();
        }
        return switch (key) {
            case "agent.reconstruction.default_v1" -> Map.of(
                    "agents", List.of("INPUT_RECONSTRUCTION_AGENT"),
                    "nodeKinds", List.of("CHAT_PRE_TOOL"),
                    "taskStates", List.of("PLANNING", "EXECUTING")
            );
            case "agent.rerank.default_v1" -> Map.of(
                    "agents", List.of("GLOBAL_CONTEXT_RERANK_AGENT"),
                    "nodeKinds", List.of("CHAT_PRE_TOOL")
            );
            case "agent.recovery.default_v1" -> Map.of(
                    "agents", List.of("RECOVERY_CONTEXT_AGENT")
            );
            case "agent.tool_semantic.default_v1" -> Map.of(
                    "agents", List.of("TOOL_SEMANTIC_AGENT"),
                    "nodeKinds", List.of("TOOL_DECISION", "CHAT_TURN")
            );
            case "agent.summary.default_v1" -> Map.of(
                    "agents", List.of("SUMMARY_AGENT"),
                    "nodeKinds", List.of("CHAT_TURN")
            );
            case "repair.main_json_v1" -> Map.of(
                    "agents", List.of("MAIN_MODEL_REPAIR_AGENT")
            );
            case "tool.args_v1" -> Map.of(
                    "agents", List.of("TOOL_DECISION_AGENT")
            );
            case "planner.master_v1" -> Map.of(
                    "agents", List.of("MASTER_PLANNING_AGENT")
            );
            case "rag.planner.query_v1", "rag.planner.source_process_v1", "rag.planner.agent_stage_v1", "rag.planner.global_rerank_v1" -> Map.of(
                    "agents", List.of("RAG_PLANNER_AGENT")
            );
            default -> Map.of();
        };
    }

    private List<CategorySeed> categorySeeds() {
        return List.of(
                new CategorySeed("system", "System", "System level prompts", 160, false, true),
                new CategorySeed("persona", "Persona", "Persona behavior prompts", 150, true, false),
                new CategorySeed("scene", "Scene", "Scene atmosphere prompts", 145, true, false),
                new CategorySeed("corpus", "Corpus", "Corpus style prompts", 140, true, false),
                new CategorySeed("style", "Style", "Expression style prompts", 135, true, false),
                new CategorySeed("worldview", "Worldview", "World setting prompts", 130, true, false),
                new CategorySeed("relation", "Relation", "Relationship prompts", 125, true, false),
                new CategorySeed("task", "Task", "Task strategy prompts", 120, false, true),
                new CategorySeed("memory-hint", "Memory Hint", "Memory usage hints", 115, false, true),
                new CategorySeed("rag-hint", "RAG Hint", "Retrieval usage hints", 110, false, true),
                new CategorySeed("tool", "Tool", "Tool execution prompts", 105, false, true),
                new CategorySeed("format", "Format", "Output format prompts", 100, false, true),
                new CategorySeed("repair", "Repair", "Repair prompts", 95, false, true),
                new CategorySeed("summary", "Summary", "Summary prompts", 90, false, true),
                new CategorySeed("guardrail", "Guardrail", "Guardrail prompts", 85, false, true),
                new CategorySeed("agent-local", "Agent Local", "Agent local prompts", 80, false, true)
        );
    }

    private record CategorySeed(String categoryKey,
                                String categoryName,
                                String description,
                                int sortOrder,
                                boolean keywordMatchAllowed,
                                boolean executionCategory) {
    }
}
