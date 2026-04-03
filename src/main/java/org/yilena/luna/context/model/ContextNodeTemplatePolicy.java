package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.LinkedHashMap;
import java.util.Map;

@Value
@Builder
public class ContextNodeTemplatePolicy {
    String nodeType;
    String nodeKind;
    String templateKey;
    String currentNodeId;
    boolean includeWorkingMemory;
    boolean includeRuntimeMemory;
    boolean includeRetrievedMemory;
    boolean includeLongTermMemory;
    int maxWorkingMemoryItems;
    int maxRuntimeMemoryItems;
    int maxRetrievedMemoryItems;
    int maxLongTermMemoryItems;
    Map<String, Integer> sectionBudgetOverrides;

    public static ContextNodeTemplatePolicy defaultPolicy() {
        return forTaskNode(null, "", "");
    }

    public static ContextNodeTemplatePolicy forTaskStage(TaskRuntimeState taskState, String currentNodeId) {
        return forTaskNode(taskState, currentNodeId, "");
    }

    public static ContextNodeTemplatePolicy forTaskNode(TaskRuntimeState taskState, String currentNodeId, String nodeKind) {
        TaskRuntimeState stage = taskState == null ? TaskRuntimeState.UNDERSTANDING : taskState;
        String normalizedNodeKind = nodeKind == null ? "" : nodeKind.trim().toUpperCase();
        String templateKey = normalizedNodeKind.isBlank() ? stage.name() : stage.name() + ":" + normalizedNodeKind;
        Map<String, Integer> sectionOverrides = new LinkedHashMap<>();
        ContextNodeTemplatePolicyBuilder builder = builder()
                .nodeType(stage.name())
                .nodeKind(normalizedNodeKind)
                .templateKey(templateKey)
                .currentNodeId(currentNodeId == null ? "" : currentNodeId)
                .includeWorkingMemory(true)
                .includeRuntimeMemory(true)
                .includeRetrievedMemory(false)
                .includeLongTermMemory(false)
                .maxWorkingMemoryItems(8)
                .maxRuntimeMemoryItems(8)
                .maxRetrievedMemoryItems(6)
                .maxLongTermMemoryItems(6)
                .sectionBudgetOverrides(sectionOverrides);
        switch (stage) {
            case PLANNING, REPLANNING -> {
                builder.includeRetrievedMemory(true).includeLongTermMemory(true)
                        .maxRetrievedMemoryItems(8).maxLongTermMemoryItems(8);
                sectionOverrides.put("Memory Hints", 1800);
            }
            case EXECUTING, CONTEXT_BUILDING, REFLECTING -> {
                builder.includeRetrievedMemory(true).includeLongTermMemory(false)
                        .maxRetrievedMemoryItems(10).maxLongTermMemoryItems(4);
                sectionOverrides.put("Memory Hints", 1600);
            }
            case WAITING_APPROVAL, WAITING_TOOL, WAITING_USER, WAITING_PLAN_CONFIRMATION -> {
                builder.includeRetrievedMemory(false).includeLongTermMemory(false)
                        .maxWorkingMemoryItems(6).maxRuntimeMemoryItems(6);
                sectionOverrides.put("Memory Hints", 1000);
                sectionOverrides.put("Relevant Knowledge Evidence", 1800);
            }
            case REPORTING, COMPLETED -> {
                builder.includeRetrievedMemory(true).includeLongTermMemory(true)
                        .maxWorkingMemoryItems(6).maxRuntimeMemoryItems(5)
                        .maxRetrievedMemoryItems(6).maxLongTermMemoryItems(8);
                sectionOverrides.put("Memory Hints", 1400);
            }
            default -> {
                sectionOverrides.put("Memory Hints", 1200);
            }
        }

        switch (normalizedNodeKind) {
            case "TOOL", "RESOURCE", "WORKFLOW" -> {
                builder.includeRetrievedMemory(true)
                        .maxRetrievedMemoryItems(10);
                sectionOverrides.put("Tool Evidence", 1800);
                sectionOverrides.put("Relevant Knowledge Evidence", 2200);
            }
            case "PROMPT" -> {
                builder.includeLongTermMemory(true);
                sectionOverrides.put("MCP Resource / Prompt Hints", 1800);
                sectionOverrides.put("Output Constraints", 280);
            }
            case "VALIDATE" -> {
                builder.includeRetrievedMemory(true)
                        .includeLongTermMemory(true)
                        .maxRuntimeMemoryItems(6);
                sectionOverrides.put("Output Constraints", 340);
                sectionOverrides.put("Current Task State", 2200);
            }
            case "REPORT", "CODE", "ANALYZE" -> {
                builder.includeRetrievedMemory(true).includeLongTermMemory(true);
                sectionOverrides.put("Relevant Knowledge Evidence", 2600);
                sectionOverrides.put("Memory Hints", 1800);
            }
            default -> {
            }
        }
        return builder.build();
    }
}
