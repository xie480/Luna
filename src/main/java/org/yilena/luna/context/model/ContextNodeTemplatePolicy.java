package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 该模型用于描述不同任务节点的上下文组装策略，控制各类记忆和证据的纳入范围及预算。
 */
@Value
@Builder
public class ContextNodeTemplatePolicy {
    /**
     * 当前策略对应的节点类型。
     */
    String nodeType;
    /**
     * 当前节点的细分业务类型。
     */
    String nodeKind;
    /**
     * 组装提示词时使用的模板键。
     */
    String templateKey;
    /**
     * 当前处理节点的唯一标识。
     */
    String currentNodeId;
    /**
     * 负责消费该上下文的提示词代理名称。
     */
    String promptAgent;
    /**
     * 是否纳入工作记忆。
     */
    boolean includeWorkingMemory;
    /**
     * 是否纳入运行时记忆。
     */
    boolean includeRuntimeMemory;
    /**
     * 是否纳入检索得到的记忆。
     */
    boolean includeRetrievedMemory;
    /**
     * 是否纳入长期记忆。
     */
    boolean includeLongTermMemory;
    /**
     * 工作记忆最大条目数。
     */
    int maxWorkingMemoryItems;
    /**
     * 运行时记忆最大条目数。
     */
    int maxRuntimeMemoryItems;
    /**
     * 检索记忆最大条目数。
     */
    int maxRetrievedMemoryItems;
    /**
     * 长期记忆最大条目数。
     */
    int maxLongTermMemoryItems;
    /**
     * 针对特定章节的预算覆盖配置。
     */
    Map<String, Integer> sectionBudgetOverrides;

    /**
     * 创建默认的任务节点上下文策略。
     */
    public static ContextNodeTemplatePolicy defaultPolicy() {
        return forTaskNode(null, "", "");
    }

    // ... existing code ...

    /**
     * 创建工具决策场景的上下文策略，重点保留工具证据和 MCP 线索。
     * <p>
     * 该策略针对工具选择和调用场景进行优化，主要特点包括：
     * 1. 为MCP资源/提示词分配较高的token预算（2400），确保能力候选信息完整
     * 2. 为工具证据分配较高预算（1800），保证工具语义分析结果充分展示
     * 3. 保留完整的任务状态（2200）和近期交互上下文（1400），提供决策依据
     * 4. 启用所有类型的记忆片段（工作记忆、运行时记忆、检索记忆、长时记忆）
     * 5. 设置合理的记忆项数量限制，平衡信息完整性和token消耗
     * <p>
     * 此策略适用于需要智能选择工具或能力的节点，如工具路由、能力匹配等场景。
     *
     * @param currentNodeId 当前节点ID，用于标识正在执行工具决策的节点，可为空
     * @return ContextNodeTemplatePolicy 配置好的工具决策策略对象，包含：
     *         - nodeType/nodeKind/templateKey: 均设置为"TOOL_DECISION"
     *         - promptAgent: 设置为"TOOL_DECISION_AGENT"
     *         - currentNodeId: 传入的节点ID
     *         - includeWorkingMemory/RuntimeMemory/RetrievedMemory/LongTermMemory: 全部启用
     *         - max*Items: 各类型记忆的最大条目数（10/10/12/8）
     *         - sectionBudgetOverrides: 各分区的token预算覆盖配置
     */
    public static ContextNodeTemplatePolicy forToolDecision(String currentNodeId) {
        Map<String, Integer> sectionOverrides = new LinkedHashMap<>();
        sectionOverrides.put("MCP Resource / Prompt Hints", 2400);
        sectionOverrides.put("Tool Evidence", 1800);
        sectionOverrides.put("Recent Interaction Context", 1400);
        sectionOverrides.put("Memory Hints", 1500);
        sectionOverrides.put("Current Task State", 2200);
        return builder()
                .nodeType("TOOL_DECISION")
                .nodeKind("TOOL_DECISION")
                .templateKey("TOOL_DECISION")
                .currentNodeId(currentNodeId == null ? "" : currentNodeId)
                .promptAgent("TOOL_DECISION_AGENT")
                .includeWorkingMemory(true)
                .includeRuntimeMemory(true)
                .includeRetrievedMemory(true)
                .includeLongTermMemory(true)
                .maxWorkingMemoryItems(10)
                .maxRuntimeMemoryItems(10)
                .maxRetrievedMemoryItems(12)
                .maxLongTermMemoryItems(8)
                .sectionBudgetOverrides(sectionOverrides)
                .build();
    }

    // ... existing code ...


    /**
     * 按任务阶段创建默认节点策略。
     */
    public static ContextNodeTemplatePolicy forTaskStage(TaskRuntimeState taskState, String currentNodeId) {
        return forTaskNode(taskState, currentNodeId, "");
    }

    /**
     * 根据任务阶段和节点类型动态生成上下文策略，决定不同阶段应重点保留哪些信息源。
     */
    public static ContextNodeTemplatePolicy forTaskNode(TaskRuntimeState taskState, String currentNodeId, String nodeKind) {
        TaskRuntimeState stage = taskState == null ? TaskRuntimeState.UNDERSTANDING : taskState;
        String normalizedNodeKind = nodeKind == null ? "" : nodeKind.trim().toUpperCase();
        String templateKey = normalizedNodeKind.isBlank() ? stage.name() : stage.name() + ":" + normalizedNodeKind;
        Map<String, Integer> sectionOverrides = new LinkedHashMap<>();
        /**
         * 先构造一份通用默认策略，后续再按阶段和节点类型做增量覆盖。
         */
        ContextNodeTemplatePolicyBuilder builder = builder()
                .nodeType(stage.name())
                .nodeKind(normalizedNodeKind)
                .templateKey(templateKey)
                .currentNodeId(currentNodeId == null ? "" : currentNodeId)
                .promptAgent("MAIN_CHAT_AGENT")
                .includeWorkingMemory(true)
                .includeRuntimeMemory(true)
                .includeRetrievedMemory(false)
                .includeLongTermMemory(false)
                .maxWorkingMemoryItems(8)
                .maxRuntimeMemoryItems(8)
                .maxRetrievedMemoryItems(6)
                .maxLongTermMemoryItems(6)
                .sectionBudgetOverrides(sectionOverrides);
        /**
         * 按任务阶段调节记忆来源和章节预算，使上下文更符合当前业务推进目标。
         */
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

        /**
         * 再按节点细分类型做补充覆盖，突出工具、校验、报告等场景特有的证据需求。
         */
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
