package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.SessionType;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.ResponseSynthesizerService;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.memory.RuntimeRetriever;
import org.yilena.luna.memory.SocialReasonerService;
import org.yilena.luna.memory.TaskMemoryRetriever;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.router.CapabilityPolicyRouterService;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.state.store.ContextStateStore;
import org.yilena.luna.state.store.RecoveryStateStore;
import org.yilena.luna.state.store.RetrievalStateStore;
import org.yilena.luna.state.store.TaskStateStore;
import org.yilena.luna.state.store.ToolStateStore;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultContextCompilerService implements ContextCompilerService {

    private final RuntimeRetriever runtimeRetriever;
    private final TaskMemoryRetriever taskMemoryRetriever;
    private final RelationalMemoryRetriever relationalMemoryRetriever;
    private final MemoryHotLayerService memoryHotLayerService;
    private final SocialReasonerService socialReasonerService;
    private final ResponseSynthesizerService responseSynthesizerService;
    private final CapabilityPolicyRouterService capabilityPolicyRouterService;
    private final TaskStateStore taskStateStore;
    private final RetrievalStateStore retrievalStateStore;
    private final ToolStateStore toolStateStore;
    private final ContextStateStore contextStateStore;
    private final RecoveryStateStore recoveryStateStore;

    @Override
    public StructuredContextPackage compile(String sessionId,
                                            String userInput,
                                            TaskRuntimeState taskState,
                                            RelationalRuntimeState relationalState) {
        StructuredContextPackage cached = memoryHotLayerService.getCompiledContextCache(sessionId, userInput, taskState, relationalState);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> runtime = runtimeRetriever.retrieve(sessionId);
        String contextualSignal = buildContextualSignal(runtime, taskStateStore.load(sessionId), retrievalStateStore.load(sessionId), toolStateStore.load(sessionId), contextStateStore.load(sessionId), taskState, relationalState);
        Map<String, Object> taskContext = taskMemoryRetriever.retrieve(sessionId, contextualSignal, taskState);
        Map<String, Object> relationalContext = relationalMemoryRetriever.retrieve(sessionId, contextualSignal, relationalState);

        List<Map<String, Object>> recentMessages = safeList(runtime.get("recent_messages"));
        List<Map<String, Object>> capabilities = capabilityPolicyRouterService.routeForContext(
                sessionId,
                contextualSignal,
                taskState,
                relationalState,
                24
        );
        Map<String, Object> socialDraft = socialReasonerService.buildRelationalDraft(
                sessionId,
                contextualSignal,
                relationalState,
                relationalContext
        );
        SessionType sessionType = resolveSessionType(runtime);
        Map<String, Object> synthesisPolicy = responseSynthesizerService.buildSynthesisPolicy(
                taskState,
                relationalState,
                taskContext,
                relationalContext,
                socialDraft
        );
        Map<String, Object> promptPolicy = buildPromptPolicy(taskState, relationalState, sessionType, socialDraft, synthesisPolicy);
        Map<String, Integer> budget = buildTokenBudget(taskState, relationalState, sessionType);

        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .sessionId(sessionId)
                .taskState(taskState)
                .relationalState(relationalState)
                .runtime(runtime)
                .taskContext(taskContext)
                .relationalContext(relationalContext)
                .recentMessages(recentMessages)
                .capabilityCandidates(capabilities)
                .promptPolicy(promptPolicy)
                .tokenBudgetPlan(budget)
                .taskStateEntity(resolveTaskState(sessionId, taskState, taskContext, runtime))
                .retrievalState(resolveRetrievalState(sessionId, taskContext))
                .toolState(resolveToolState(sessionId, runtime))
                .contextState(contextStateStore.load(sessionId))
                .recoveryState(recoveryStateStore.load(sessionId))
                .build();
        memoryHotLayerService.putCompiledContextCache(sessionId, userInput, taskState, relationalState, contextPackage);
        return contextPackage;
    }

    private String buildContextualSignal(Map<String, Object> runtime,
                                         TaskState taskStateEntity,
                                         RetrievalState retrievalState,
                                         ToolState toolState,
                                         ContextState contextState,
                                         TaskRuntimeState taskState,
                                         RelationalRuntimeState relationalState) {
        StringBuilder signal = new StringBuilder();
        signal.append("taskState=").append(taskState == null ? "UNKNOWN" : taskState.name());
        signal.append(";relationalState=").append(relationalState == null ? "UNKNOWN" : relationalState.name());
        if (taskStateEntity != null) {
            signal.append(";objective=").append(str(taskStateEntity.getObjective()));
            signal.append(";currentNode=").append(str(taskStateEntity.getCurrentNode()));
            signal.append(";pendingQuestions=").append(str(taskStateEntity.getPendingQuestions()));
        }
        if (retrievalState != null) {
            signal.append(";retrievalIntent=").append(str(retrievalState.getReconstructedIntent()));
            signal.append(";activeQueries=").append(str(retrievalState.getActiveQueries()));
        }
        if (toolState != null) {
            signal.append(";lastTool=").append(str(toolState.getLastToolName()));
            signal.append(";lastToolStatus=").append(str(toolState.getLastToolStatus()));
            signal.append(";lastToolSemantic=").append(str(toolState.getLastToolSemanticSummary()));
        }
        if (contextState != null) {
            signal.append(";latestSummary=").append(str(contextState.getLatestNarrativeSummary()));
            signal.append(";activeKnowledgeRefs=").append(str(contextState.getActiveKnowledgeRefs()));
        }
        List<Map<String, Object>> messages = safeList(runtime == null ? null : runtime.get("recent_messages"));
        if (!messages.isEmpty()) {
            int from = Math.max(0, messages.size() - 6);
            signal.append(";recentDialog=");
            for (Map<String, Object> message : messages.subList(from, messages.size())) {
                signal.append(str(message.get("role"))).append(':').append(str(message.get("content_text"))).append('|');
            }
        }
        return signal.toString();
    }

    private TaskState resolveTaskState(String sessionId, TaskRuntimeState taskRuntimeState, Map<String, Object> taskContext, Map<String, Object> runtime) {
        TaskState stored = taskStateStore.load(sessionId);
        if (stored != null) {
            return stored;
        }
        Map<String, Object> working = mapOf(taskContext == null ? null : taskContext.get("working_memory"));
        Map<String, Object> session = mapOf(runtime == null ? null : runtime.get("session"));
        return TaskState.builder()
                .taskId(str(working.get("plan_id")))
                .sessionId(sessionId)
                .objective(str(working.get("goal_refined")))
                .currentStage(taskRuntimeState == null ? "UNKNOWN" : taskRuntimeState.name())
                .currentNode(str(working.get("active_node_id")))
                .confirmedSlots(mapOf(working.get("key_entities_json")))
                .pendingQuestions(listOfStrings(working.get("unresolved_questions_json")))
                .finishedSteps(List.of())
                .failedSteps(List.of())
                .retryCount(intValue(session.get("retry_count")))
                .nextActionHint("continue")
                .build();
    }

    private RetrievalState resolveRetrievalState(String sessionId, Map<String, Object> taskContext) {
        RetrievalState stored = retrievalStateStore.load(sessionId);
        if (stored != null) {
            return stored;
        }
        Map<String, Object> working = mapOf(taskContext == null ? null : taskContext.get("working_memory"));
        return RetrievalState.builder()
                .reconstructedIntent(str(working.get("goal_refined")))
                .activeQueries(List.of())
                .retrievalPlan(Map.of())
                .selectedEvidenceRefs(List.of())
                .rerankSummary("")
                .build();
    }

    private ToolState resolveToolState(String sessionId, Map<String, Object> runtime) {
        ToolState stored = toolStateStore.load(sessionId);
        if (stored != null) {
            return stored;
        }
        List<Map<String, Object>> toolRows = safeList(runtime == null ? null : runtime.get("active_tool_results"));
        if (toolRows.isEmpty()) {
            return ToolState.builder()
                    .lastToolName("")
                    .lastToolInput("")
                    .lastToolStatus("")
                    .lastToolRawResultRef("")
                    .lastToolSemanticSummary("")
                    .toolCallHistoryRefs(List.of())
                    .build();
        }
        Map<String, Object> latest = toolRows.get(0);
        return ToolState.builder()
                .lastToolName(str(latest.get("tool_name")))
                .lastToolInput("")
                .lastToolStatus(str(latest.get("call_status")))
                .lastToolRawResultRef(str(latest.get("normalized_output")))
                .lastToolSemanticSummary("")
                .toolCallHistoryRefs(toolRows.stream().map(row -> str(row.get("tool_name"))).filter(name -> !name.isBlank()).toList())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : Collections.emptyList();
    }

    private Map<String, Object> buildPromptPolicy(TaskRuntimeState taskState,
                                                  RelationalRuntimeState relationalState,
                                                  SessionType sessionType,
                                                  Map<String, Object> socialDraft,
                                                  Map<String, Object> synthesisPolicy) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("task_mode", taskState == null ? "UNKNOWN" : taskState.name());
        policy.put("social_mode", relationalState == null ? "UNKNOWN" : relationalState.name());
        policy.put("session_type", sessionType.name());
        policy.put("planner_enabled", taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING);
        policy.put("executor_enabled", taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.REPORTING);
        policy.put("social_reasoner_enabled", true);
        policy.put("synthesis_mode", "TASK_SOCIAL_MERGE");
        policy.put("social_draft", socialDraft == null ? Map.of() : socialDraft);
        policy.put("response_synthesis", synthesisPolicy == null ? Map.of() : synthesisPolicy);
        return policy;
    }

    private Map<String, Integer> buildTokenBudget(TaskRuntimeState taskState,
                                                  RelationalRuntimeState relationalState,
                                                  SessionType sessionType) {
        Map<String, Integer> budget = new HashMap<>();
        if (relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT || relationalState == RelationalRuntimeState.FRAGILE_MOMENT) {
            budget.put("runtime", 500);
            budget.put("relational_buffer", 1000);
            budget.put("relational_working", 1800);
            budget.put("relational_profile_semantic", 1200);
            budget.put("relational_episodes", 1500);
            budget.put("relational_procedures", 1000);
            budget.put("recent_messages", 1500);
            budget.put("task_context", 500);
            budget.put("spare", 1000);
            return budget;
        }
        budget.put("runtime", 800);
        budget.put("task_buffer", sessionType == SessionType.COMPANION ? 400 : 900);
        budget.put("relational_buffer", sessionType == SessionType.TASK ? 300 : 700);
        budget.put("task_working", 2500);
        budget.put("plan_node", 2000);
        budget.put("task_facts", 1000);
        budget.put("task_procedures", 1200);
        budget.put("task_episodes", 1200);
        budget.put("knowledge", 2500);
        budget.put("relation_context", 500);
        budget.put("recent_messages", 300);
        return budget;
    }

    @SuppressWarnings("unchecked")
    private SessionType resolveSessionType(Map<String, Object> runtime) {
        if (runtime == null) {
            return SessionType.HYBRID;
        }
        Object session = runtime.get("session");
        if (session instanceof Map<?, ?> row) {
            Object raw = ((Map<String, Object>) row).get("session_type");
            return SessionType.from(raw == null ? null : String.valueOf(raw));
        }
        return SessionType.HYBRID;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> listOfStrings(Object value) {
        if (value instanceof List<?> list) {
            return (List<String>) list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignore) {
            return 0;
        }
    }
}

