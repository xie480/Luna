package org.yilena.luna.prompt.governance.support;

import java.util.Set;

/**
 * 运行时槽位词表工具类，负责定义系统允许使用的提示词运行时槽位集合，
 * 用于在提示词治理阶段约束槽位配置是否合法。
 */
public final class RuntimeSlotVocabulary {

    /**
     * 系统允许的运行时槽位集合。
     */
    private static final Set<String> ALLOWED_SLOTS = Set.of(
            "instructions.system",
            "instructions.persona",
            "instructions.scene",
            "memory.hints",
            "knowledge.evidence",
            "output.constraints",
            "runtime.prompt",
            "agent.reconstruction",
            "agent.summary",
            "repair.main",
            "agent.exception_analysis",
            "repair.exception",
            "agent.rerank",
            "agent.recovery",
            "agent.tool_semantic",
            "agent.tool_args",
            "agent.workflow_args",
            "agent.tool_args_repair",
            "agent.tool_decision",
            "agent.master_planning",
            "task.plan.final_result_to_luna",
            "rag.planner.query",
            "rag.planner.source_process",
            "rag.planner.agent_stage",
            "rag.planner.global_rerank"
    );

    private RuntimeSlotVocabulary() {
    }

    public static boolean isAllowed(String runtimeSlot) {
        String normalized = normalize(runtimeSlot);
        if (normalized.isBlank()) {
            return true;
        }
        return ALLOWED_SLOTS.contains(normalized);
    }

    public static String normalize(String runtimeSlot) {
        return runtimeSlot == null ? "" : runtimeSlot.trim().toLowerCase();
    }
}
