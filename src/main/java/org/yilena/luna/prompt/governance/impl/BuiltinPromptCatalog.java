package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.prompt.governance.model.EditPolicy;
import org.yilena.luna.prompt.governance.model.MatchScope;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BuiltinPromptCatalog {

    private BuiltinPromptCatalog() {
    }

    static Map<String, PromptItemRecord> all() {
        Map<String, PromptItemRecord> out = new LinkedHashMap<>();
        out.put("system.base.default_v1", item(
                "system.base.default_v1",
                "System Base",
                PromptTemplates.SYSTEM_PROMPT,
                "system",
                "base",
                "instructions.system",
                false,
                false,
                List.of(),
                "ALWAYS",
                "1.0.0",
                "System prompt fallback"
        ));
        out.put("task.runtime.main_v1", item(
                "task.runtime.main_v1",
                "Runtime Main",
                PromptTemplates.RUNTIME_PROMPT,
                "task",
                "runtime",
                "runtime.prompt",
                true,
                false,
                List.of("runtimePromptInput"),
                "ALWAYS",
                "1.0.0",
                "Runtime prompt fallback"
        ));
        out.put("repair.main.json_v1", item(
                "repair.main.json_v1",
                "Repair Main Json",
                PromptTemplates.REPAIR_PROMPT,
                "repair",
                "json",
                "repair.main",
                true,
                false,
                List.of("invalidJson"),
                "AGENT_ONLY",
                "1.0.0",
                "Repair prompt fallback"
        ));
        out.put("task.exception.analysis_v1", item(
                "task.exception.analysis_v1",
                "Exception Analysis",
                PromptTemplates.EXCEPTION_ANALYSIS_PROMPT,
                "task",
                "exception",
                "agent.exception_analysis",
                true,
                false,
                List.of(),
                "MANUAL_ONLY",
                "1.0.0",
                "Exception analysis fallback"
        ));
        out.put("repair.exception.json_v1", item(
                "repair.exception.json_v1",
                "Exception Json Repair",
                PromptTemplates.EXCEPTION_JSON_REPAIR_PROMPT,
                "repair",
                "exception",
                "repair.exception",
                true,
                false,
                List.of(),
                "MANUAL_ONLY",
                "1.0.0",
                "Exception json repair fallback"
        ));
        out.put("agent-local.reconstruction.default_v1", item(
                "agent-local.reconstruction.default_v1",
                "Agent Reconstruction",
                "",
                "agent-local",
                "reconstruction",
                "agent.reconstruction",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Agent reconstruction fallback"
        ));
        out.put("agent-local.rerank.default_v1", item(
                "agent-local.rerank.default_v1",
                "Agent Rerank",
                "",
                "agent-local",
                "rerank",
                "agent.rerank",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Agent rerank fallback"
        ));
        out.put("agent-local.recovery.default_v1", item(
                "agent-local.recovery.default_v1",
                "Agent Recovery",
                "",
                "agent-local",
                "recovery",
                "agent.recovery",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Agent recovery fallback"
        ));
        out.put("agent-local.tool-semantic.default_v1", item(
                "agent-local.tool-semantic.default_v1",
                "Agent Tool Semantic",
                "",
                "agent-local",
                "tool-semantic",
                "agent.tool_semantic",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Tool semantic fallback"
        ));
        out.put("agent-local.summary.default_v1", item(
                "agent-local.summary.default_v1",
                "Agent Summary",
                "",
                "agent-local",
                "summary",
                "agent.summary",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Summary fallback"
        ));
        out.put("tool.args.default_v1", item(
                "tool.args.default_v1",
                "Tool Args",
                PromptTemplates.TOOL_ARGS_PROMPT,
                "tool",
                "args",
                "agent.tool_args",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Tool args fallback"
        ));
        out.put("workflow.args.default_v1", item(
                "workflow.args.default_v1",
                "Workflow Args",
                PromptTemplates.SKILL_ARGS_PROMPT,
                "tool",
                "workflow",
                "agent.workflow_args",
                true,
                false,
                List.of(),
                "MANUAL_ONLY",
                "1.0.0",
                "Workflow args fallback"
        ));
        out.put("tool.args.repair.json_v1", item(
                "tool.args.repair.json_v1",
                "Tool Args Repair",
                PromptTemplates.TOOL_ARGS_REPAIR_PROMPT,
                "tool",
                "args",
                "agent.tool_args_repair",
                true,
                false,
                List.of(),
                "MANUAL_ONLY",
                "1.0.0",
                "Tool args repair fallback"
        ));
        out.put("tool.decision.default_v1", item(
                "tool.decision.default_v1",
                "Tool Decision",
                """
                        You are a tool decision agent. Decide the next action strictly from the assembled decision workset.
                        The workset already contains node state, MCP hints, constraints and recent tool semantics.
                        Return exactly one JSON object, no markdown.

                        Action JSON:
                        {"action_type":"tool_call|prompt_get|resource_read|workflow_start|direct_answer","target_name":"...","arguments":{...}}
                        or
                        {"action_type":"direct_answer","answer":"..."}
                        or
                        {"action_type":"none","target_name":"none"}

                        Assembled Decision Workset:
                        %s
                        """,
                "tool",
                "decision",
                "agent.tool_decision",
                true,
                false,
                List.of("assembledDecisionContext"),
                "AGENT_ONLY",
                "1.0.0",
                "Tool decision fallback"
        ));
        out.put("task.planner.master_v1", item(
                "task.planner.master_v1",
                "Master Planning",
                PromptTemplates.MASTER_PLANNING_PROMPT,
                "task",
                "planner",
                "agent.master_planning",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Master planning fallback"
        ));
        out.put("task.plan.final_result_to_luna_v1", item(
                "task.plan.final_result_to_luna_v1",
                "Plan Final Result To Luna",
                PromptTemplates.PLAN_FINAL_RESULT_TO_LUNA_PROMPT,
                "task",
                "plan",
                "task.plan.final_result_to_luna",
                true,
                false,
                List.of(),
                "MANUAL_ONLY",
                "1.0.0",
                "Plan final callback fallback"
        ));
        out.put("format.chat.json_v2", item(
                "format.chat.json_v2",
                "Chat Json Format",
                """
                        Always return a single valid JSON object.
                        Do not wrap JSON in markdown code fences.
                        Ensure all required fields from Output Constraints are present.
                        """,
                "format",
                "chat",
                "output.constraints",
                false,
                false,
                List.of(),
                "ALWAYS",
                "2.0.0",
                "Execution format baseline"
        ));
        out.put("guardrail.safe.chat_v1", item(
                "guardrail.safe.chat_v1",
                "Safe Chat Guardrail",
                """
                        Do not fabricate capabilities, data, or execution results.
                        If required data is missing, state it explicitly and request clarification.
                        Refuse unsafe or policy-violating instructions.
                        """,
                "guardrail",
                "safe",
                "output.constraints",
                false,
                false,
                List.of(),
                "ALWAYS",
                "1.0.0",
                "Execution guardrail baseline"
        ));
        out.put("memory-hint.default_v1", item(
                "memory-hint.default_v1",
                "Memory Hint Default",
                "Prioritize stable memory facts and summary over noisy recent fragments; avoid leaking raw user text.",
                "memory-hint",
                "default",
                "memory.hints",
                false,
                false,
                List.of(),
                "ALWAYS",
                "1.0.0",
                "Execution memory hint baseline"
        ));
        out.put("rag-hint.default_v1", item(
                "rag-hint.default_v1",
                "Rag Hint Default",
                "Prefer high-confidence evidence blocks, keep source references, and avoid overgeneralizing from weak matches.",
                "rag-hint",
                "default",
                "knowledge.evidence",
                false,
                false,
                List.of(),
                "ALWAYS",
                "1.0.0",
                "Execution rag hint baseline"
        ));
        out.put("rag.planner.query_v1", item(
                "rag.planner.query_v1",
                "Rag Planner Query",
                "",
                "rag-hint",
                "planner",
                "rag.planner.query",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Rag query planner fallback"
        ));
        out.put("rag.planner.source_process_v1", item(
                "rag.planner.source_process_v1",
                "Rag Source Process Planner",
                "",
                "rag-hint",
                "planner",
                "rag.planner.source_process",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Rag source process planner fallback"
        ));
        out.put("rag.planner.agent_stage_v1", item(
                "rag.planner.agent_stage_v1",
                "Rag Agent Stage Planner",
                "",
                "rag-hint",
                "planner",
                "rag.planner.agent_stage",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Rag agent stage planner fallback"
        ));
        out.put("rag.planner.global_rerank_v1", item(
                "rag.planner.global_rerank_v1",
                "Rag Global Rerank Planner",
                "",
                "rag-hint",
                "planner",
                "rag.planner.global_rerank",
                true,
                false,
                List.of(),
                "AGENT_ONLY",
                "1.0.0",
                "Rag global rerank planner fallback"
        ));
        return out;
    }

    private static PromptItemRecord item(String key,
                                         String name,
                                         String value,
                                         String category,
                                         String subCategory,
                                         String runtimeSlot,
                                         boolean hasTemplateVariables,
                                         boolean keywordMatchEnabled,
                                         List<String> templateVariables,
                                         String assemblyMode,
                                         String version,
                                         String description) {
        return PromptItemRecord.builder()
                .itemId(-1L)
                .versionId(-1L)
                .key(key)
                .name(name)
                .value(value)
                .category(category)
                .subCategory(subCategory)
                .description(description)
                .runtimeSlot(runtimeSlot)
                .hasTemplateVariables(hasTemplateVariables)
                .templateVariables(templateVariables)
                .keywordMatchEnabled(keywordMatchEnabled)
                .matchKeywords(List.of())
                .assemblyMode(assemblyMode)
                .matchScope(MatchScope.empty())
                .editPolicy(EditPolicy.builder()
                        .create(!hasTemplateVariables)
                        .update(true)
                        .delete(!hasTemplateVariables)
                        .build())
                .enabled(true)
                .priority(hasTemplateVariables ? 100 : 80)
                .status("enabled")
                .version(version)
                .versionLabel(version)
                .changeNote("builtin_fallback")
                .build();
    }
}
