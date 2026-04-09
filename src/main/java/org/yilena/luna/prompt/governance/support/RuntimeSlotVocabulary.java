package org.yilena.luna.prompt.governance.support;

import java.util.Set;

public final class RuntimeSlotVocabulary {

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
