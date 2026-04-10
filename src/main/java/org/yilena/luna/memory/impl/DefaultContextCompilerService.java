package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.SessionType;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.memory.ResponseSynthesizerService;
import org.yilena.luna.memory.RuntimeRetriever;
import org.yilena.luna.memory.SocialReasonerService;
import org.yilena.luna.memory.TaskMemoryRetriever;
import org.yilena.luna.memory.model.ContextCompileOptions;
import org.yilena.luna.memory.model.GovernedSignal;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.memory.support.ToolRawRefResolver;
import org.yilena.luna.state.model.ContextState;
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
import java.util.Locale;
import java.security.MessageDigest;

@Service
@RequiredArgsConstructor
/**
 * 上下文编译服务默认实现，负责从运行态、状态存储和记忆检索中拼装结构化上下文包，
 * 为后续意图重构、重排和响应生成提供统一输入。
 */
public class DefaultContextCompilerService implements ContextCompilerService {
    private static final String DEFAULT_PROMPT_POLICY_ID = "chat_default_v1";
    private static final String DEFAULT_COMPANION_PROMPT_POLICY_ID = "chat_tavern_default_v1";

    private final RuntimeRetriever runtimeRetriever;
    private final MemoryHotLayerService memoryHotLayerService;
    private final TaskMemoryRetriever taskMemoryRetriever;
    private final RelationalMemoryRetriever relationalMemoryRetriever;
    private final SocialReasonerService socialReasonerService;
    private final ResponseSynthesizerService responseSynthesizerService;
    private final TaskStateStore taskStateStore;
    private final RetrievalStateStore retrievalStateStore;
    private final ToolStateStore toolStateStore;
    private final ContextStateStore contextStateStore;
    private final RecoveryStateStore recoveryStateStore;
    private final ObjectMapper objectMapper;

    @Value("${memory.context.compiler.fallback-preload-enabled:false}")
    private boolean fallbackPreloadEnabled;

    @Override
    /**
     * 编译当前会话的结构化上下文，必要时复用热层缓存以降低重复构建成本。
     */
    public StructuredContextPackage compile(String sessionId,
                                            String userInput,
                                            TaskRuntimeState taskState,
                                            RelationalRuntimeState relationalState,
                                            ContextCompileOptions options) {
        /**
         * 优先命中已编译上下文缓存，
         * 避免同一轮请求在短时间内重复加载状态和记忆。
         */
        if (isCacheEligible(options)) {
            StructuredContextPackage cached = memoryHotLayerService.getCompiledContextCache(sessionId, userInput, taskState, relationalState);
            if (cached != null) {
                return cached;
            }
        }

        /**
         * 从运行态和状态仓储收集任务、检索、工具与上下文状态，
         * 为后续预加载决策和提示策略构建提供基础事实。
         */
        Map<String, Object> runtime = runtimeRetriever.retrieve(sessionId);
        TaskState storedTaskState = taskStateStore.load(sessionId);
        RetrievalState storedRetrievalState = retrievalStateStore.load(sessionId);
        ToolState storedToolState = toolStateStore.load(sessionId);
        ContextState storedContextState = contextStateStore.load(sessionId);
        String contextualSignal = buildContextualSignal(
                runtime,
                storedTaskState,
                storedRetrievalState,
                storedToolState,
                storedContextState,
                taskState,
                relationalState
        );
        GovernedSignal governedSignal = extractGovernedSignal(userInput);
        PreloadDecision preloadDecision = resolvePreloadDecision(taskState, options);
        /**
         * 按预加载策略分别补齐任务记忆和关系记忆，
         * 让不同场景在性能与上下文完整度之间取得平衡。
         */
        Map<String, Object> taskContext = preloadTaskContext(
                sessionId,
                governedSignal,
                taskState,
                storedTaskState,
                storedRetrievalState,
                runtime,
                preloadDecision.preloadTaskMemory()
        );
        Map<String, Object> relationalContext = preloadRelationalContext(
                sessionId,
                governedSignal,
                relationalState,
                storedContextState,
                runtime,
                preloadDecision.preloadRelationalMemory()
        );

        List<Map<String, Object>> recentMessages = safeList(runtime.get("recent_messages"));
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
        promptPolicy.put("memory_fetch_mode", preloadDecision.memoryFetchMode());
        promptPolicy.put("capability_fetch_mode", "MCP_QUERY_ON_DEMAND");
        promptPolicy.put("compiler_preload_mode", preloadDecision.preloadMode());
        promptPolicy.put("compiler_preload_reason", preloadDecision.reason());
        promptPolicy.put("compiler_preload_task_memory", preloadDecision.preloadTaskMemory());
        promptPolicy.put("compiler_preload_relational_memory", preloadDecision.preloadRelationalMemory());
        Map<String, Integer> budget = buildTokenBudget(taskState, relationalState, sessionType);

        /**
         * 汇总各类上下文字段并生成最终结构化上下文包，
         * 同时回填状态实体，供后续链路直接读取。
         */
        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .sessionId(sessionId)
                .taskState(taskState)
                .relationalState(relationalState)
                .runtime(runtime)
                .taskContext(taskContext)
                .relationalContext(relationalContext)
                .recentMessages(recentMessages)
                .capabilityCandidates(List.of())
                .promptPolicy(promptPolicy)
                .tokenBudgetPlan(budget)
                .taskStateEntity(resolveTaskState(sessionId, taskState, taskContext, runtime))
                .retrievalState(resolveRetrievalState(sessionId, taskContext, runtime))
                .toolState(resolveToolState(sessionId, runtime))
                .contextState(storedContextState)
                .recoveryState(recoveryStateStore.load(sessionId))
                .build();
        if (isCacheEligible(options)) {
            memoryHotLayerService.putCompiledContextCache(sessionId, userInput, taskState, relationalState, contextPackage);
        }
        return contextPackage;
    }

    private Map<String, Object> preloadTaskContext(String sessionId,
                                                   GovernedSignal governedSignal,
                                                   TaskRuntimeState taskState,
                                                   TaskState storedTaskState,
                                                   RetrievalState storedRetrievalState,
                                                   Map<String, Object> runtime,
                                                   boolean preloadEnabled) {
        /**
         * 任务记忆预加载阶段根据治理信号与当前状态生成语义检索词，
         * 只在允许预加载时提前补齐任务相关上下文。
         */
        if (isBlank(sessionId)) {
            return Map.of();
        }
        if (!preloadEnabled) {
            return Map.of(
                    "compiler_preloaded", false,
                    "preload_mode", "minimal_runtime_state_only"
            );
        }
        String semanticQuery = buildTaskSemanticQuery(governedSignal, storedTaskState, storedRetrievalState, runtime);
        Map<String, Object> retrieved = mapOf(taskMemoryRetriever.retrieve(sessionId, semanticQuery, taskState));
        if (retrieved.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>(retrieved);
        merged.put("compiler_preloaded", true);
        merged.put("semantic_query", semanticQuery);
        return merged;
    }

    private Map<String, Object> preloadRelationalContext(String sessionId,
                                                         GovernedSignal governedSignal,
                                                         RelationalRuntimeState relationalState,
                                                         ContextState storedContextState,
                                                         Map<String, Object> runtime,
                                                         boolean preloadEnabled) {
        /**
         * 关系记忆预加载阶段聚合近期对话和叙事摘要，
         * 为社交推理和回复风格控制提供关系层事实。
         */
        if (isBlank(sessionId)) {
            return Map.of();
        }
        if (!preloadEnabled) {
            return Map.of(
                    "compiler_preloaded", false,
                    "preload_mode", "minimal_runtime_state_only"
            );
        }
        String semanticQuery = buildRelationalSemanticQuery(governedSignal, storedContextState, runtime);
        Map<String, Object> retrieved = mapOf(relationalMemoryRetriever.retrieve(sessionId, semanticQuery, relationalState));
        if (retrieved.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>(retrieved);
        merged.put("compiler_preloaded", true);
        merged.put("semantic_query", semanticQuery);
        return merged;
    }

    private String buildTaskSemanticQuery(GovernedSignal governedSignal,
                                          TaskState storedTaskState,
                                          RetrievalState storedRetrievalState,
                                          Map<String, Object> runtime) {
        String intent = governedSignal == null ? "" : str(governedSignal.getIntent());
        String goalFromSignal = governedSignal == null ? "" : str(governedSignal.getGoal());
        String timeScope = governedSignal == null ? "" : str(governedSignal.getTimeScope());
        String objective = storedTaskState == null ? "" : str(storedTaskState.getObjective());
        String node = storedTaskState == null ? "" : str(storedTaskState.getCurrentNode());
        String retrievalIntent = storedRetrievalState == null ? "" : str(storedRetrievalState.getReconstructedIntent());
        Map<String, Object> session = mapOf(runtime == null ? null : runtime.get("session"));
        String currentGoal = str(session.get("current_goal"));
        return String.join(" | ",
                "explicit_task_goal=" + firstNonBlank(goalFromSignal, firstNonBlank(objective, currentGoal)),
                "normalized_intent=" + firstNonBlank(intent, "intent_unavailable"),
                "time_scope=" + firstNonBlank(timeScope, "unspecified"),
                "node=" + firstNonBlank(node, "unknown"),
                "retrieval_intent=" + firstNonBlank(retrievalIntent, "none"),
                "query_source=context_compiler_preload");
    }

    private String buildRelationalSemanticQuery(GovernedSignal governedSignal,
                                                ContextState storedContextState,
                                                Map<String, Object> runtime) {
        String intent = governedSignal == null ? "" : str(governedSignal.getIntent());
        String goal = governedSignal == null ? "" : str(governedSignal.getGoal());
        String narrative = storedContextState == null ? "" : str(storedContextState.getLatestNarrativeSummary());
        List<Map<String, Object>> messages = safeList(runtime == null ? null : runtime.get("recent_messages"));
        String recentSignal = "";
        if (!messages.isEmpty()) {
            Map<String, Object> latest = messages.get(messages.size() - 1);
            recentSignal = str(latest.get("content_text"));
        }
        return String.join(" | ",
                "normalized_intent=" + firstNonBlank(intent, "intent_unavailable"),
                "explicit_task_goal=" + firstNonBlank(goal, "goal_unavailable"),
                "recent_signal=" + firstNonBlank(recentSignal, "none"),
                "narrative_summary=" + firstNonBlank(narrative, "none"),
                "query_source=context_compiler_preload");
    }

    private GovernedSignal extractGovernedSignal(String signalPayload) {
        /**
         * 优先按结构化信号解析用户输入，
         * 解析失败时再退回原始文本治理，保证上下文编译始终可继续。
         */
        if (isBlank(signalPayload)) {
            return GovernedSignal.fromRawInput("");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapOf(objectMapper.readValue(signalPayload, Map.class));
            return GovernedSignal.builder()
                    .debugFlag(boolValue(map.get("debugFlag")))
                    .intent(firstNonBlank(str(map.get("intent")), "intent_unavailable"))
                    .goal(firstNonBlank(str(map.get("goal")), "goal_unavailable"))
                    .constraints(listOfStrings(map.get("constraints")))
                    .timeScope(firstNonBlank(str(map.get("timeScope")), "unspecified"))
                    .missingSlots(listOfStrings(map.get("missingSlots")))
                    .fallback(firstNonBlank(str(map.get("fallback")), "reconstruct_retry_required"))
                    .build();
        } catch (Exception ignore) {
            return GovernedSignal.fromRawInput(signalPayload);
        }
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
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
        List<Map<String, Object>> snapshots = safeList(runtime == null ? null : runtime.get("context_snapshots"));
        String snapshotNodeId = snapshots.isEmpty() ? "" : str(snapshots.get(0).get("node_id"));
        return TaskState.builder()
                .taskId(firstNonBlank(str(working.get("plan_id")), str(session.get("current_plan_id"))))
                .sessionId(sessionId)
                .objective(firstNonBlank(str(working.get("goal_refined")), str(session.get("current_goal"))))
                .currentStage(taskRuntimeState == null ? "UNKNOWN" : taskRuntimeState.name())
                .currentNode(firstNonBlank(str(working.get("active_node_id")), snapshotNodeId))
                .confirmedSlots(mapOf(working.get("key_entities_json")))
                .pendingQuestions(listOfStrings(working.get("unresolved_questions_json")))
                .finishedSteps(List.of())
                .failedSteps(List.of())
                .retryCount(intValue(session.get("retry_count")))
                .nextActionHint("continue")
                .build();
    }

    private RetrievalState resolveRetrievalState(String sessionId, Map<String, Object> taskContext, Map<String, Object> runtime) {
        RetrievalState stored = retrievalStateStore.load(sessionId);
        if (stored != null) {
            return stored;
        }
        Map<String, Object> working = mapOf(taskContext == null ? null : taskContext.get("working_memory"));
        Map<String, Object> session = mapOf(runtime == null ? null : runtime.get("session"));
        return RetrievalState.builder()
                .reconstructedIntent(firstNonBlank(str(working.get("goal_refined")), str(session.get("current_goal"))))
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
                    .lastToolRawPayloadRef("")
                    .lastToolRawResult("")
                    .lastToolRawResultDigest("")
                    .lastToolRawResultPreview("")
                    .lastToolSemanticSummary("")
                    .toolCallHistoryRefs(List.of())
                    .build();
        }
        Map<String, Object> latest = toolRows.get(0);
        String traceId = str(latest.get("trace_id"));
        String toolName = str(latest.get("tool_name"));
        String callStatus = str(latest.get("call_status")).toUpperCase(Locale.ROOT);
        String rawRef = traceId.isBlank()
                ? (toolName.isBlank() ? "tool_execution_trace:latest" : "tool_execution_trace:" + toolName + ":" + (callStatus.isBlank() ? "UNKNOWN" : callStatus))
                : "tool_execution_trace:id=" + traceId;
        String rawJson = ToolRawRefResolver.resolveRawJson(rawRef, toolRows, objectMapper);
        if (rawJson.isBlank()) {
            rawJson = normalizeJsonString(latest.get("normalized_output"));
        }
        return ToolState.builder()
                .lastToolName(toolName)
                .lastToolInput("")
                .lastToolStatus(callStatus)
                .lastToolRawResultRef(rawRef)
                .lastToolRawPayloadRef("")
                .lastToolRawResult(rawJson)
                .lastToolRawResultDigest(sha256Hex(rawJson))
                .lastToolRawResultPreview(truncate(rawJson, 320))
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
        /**
         * 将任务模式、关系模式和响应合成策略汇总为统一提示策略，
         * 供提示词治理和上下文装配阶段继续消费。
         */
        Map<String, Object> policy = new LinkedHashMap<>();
        String policyId = resolvePromptPolicyId(sessionType, socialDraft, synthesisPolicy);
        policy.put("task_mode", taskState == null ? "UNKNOWN" : taskState.name());
        policy.put("social_mode", relationalState == null ? "UNKNOWN" : relationalState.name());
        policy.put("session_type", sessionType.name());
        policy.put("policyId", policyId);
        policy.put("policy_id", policyId);
        policy.put("planner_enabled", taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING);
        policy.put("executor_enabled", taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.REPORTING);
        policy.put("social_reasoner_enabled", true);
        policy.put("synthesis_mode", "TASK_SOCIAL_MERGE");
        policy.put("social_draft", socialDraft == null ? Map.of() : socialDraft);
        policy.put("response_synthesis", synthesisPolicy == null ? Map.of() : synthesisPolicy);
        return policy;
    }

    private String resolvePromptPolicyId(SessionType sessionType,
                                         Map<String, Object> socialDraft,
                                         Map<String, Object> synthesisPolicy) {
        String bySynthesis = readStringByAlias(synthesisPolicy, "policyId", "policy_id", "promptPolicyId", "prompt_policy_id");
        if (!bySynthesis.isBlank()) {
            return bySynthesis;
        }
        String bySocial = readStringByAlias(socialDraft, "policyId", "policy_id", "promptPolicyId", "prompt_policy_id");
        if (!bySocial.isBlank()) {
            return bySocial;
        }
        return sessionType == SessionType.COMPANION
                ? DEFAULT_COMPANION_PROMPT_POLICY_ID
                : DEFAULT_PROMPT_POLICY_ID;
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

    private String readStringByAlias(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object raw = source.get(key);
            if (raw != null) {
                String value = String.valueOf(raw).trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String normalizeJsonString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignore) {
            return String.valueOf(value);
        }
    }

    private String truncate(String text, int maxLen) {
        String normalized = text == null ? "" : text;
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen));
    }

    private String sha256Hex(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ignore) {
            return "";
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isCacheEligible(ContextCompileOptions options) {
        if (options == null) {
            return true;
        }
        ContextCompileOptions.PreloadMode mode = options.getPreloadMode();
        if (mode != null && mode != ContextCompileOptions.PreloadMode.AUTO) {
            return false;
        }
        return options.getFallbackPreloadEnabled() == null;
    }

    private PreloadDecision resolvePreloadDecision(TaskRuntimeState taskState, ContextCompileOptions options) {
        /**
         * 根据显式配置、灰度开关和当前任务阶段决定是否预加载记忆，
         * 避免在低收益场景无谓放大上下文成本。
         */
        ContextCompileOptions effective = options == null ? ContextCompileOptions.auto() : options;
        ContextCompileOptions.PreloadMode mode = effective.getPreloadMode() == null
                ? ContextCompileOptions.PreloadMode.AUTO
                : effective.getPreloadMode();
        boolean fallbackEnabled = effective.getFallbackPreloadEnabled() == null
                ? fallbackPreloadEnabled
                : effective.getFallbackPreloadEnabled();
        if (mode == ContextCompileOptions.PreloadMode.FULL) {
            return new PreloadDecision(true, true, "FULL", "explicit_full_preload", "ENTRY_PRELOADED_PLUS_NODE_ON_DEMAND");
        }
        if (mode == ContextCompileOptions.PreloadMode.MINIMAL) {
            return new PreloadDecision(false, false, "MINIMAL", "explicit_minimal_preload", "MIN_RUNTIME_STATE_PLUS_NODE_ON_DEMAND");
        }
        if (fallbackEnabled && shouldAutoFallbackPreload(taskState)) {
            return new PreloadDecision(true, true, "AUTO", "fallback_gray_preload", "ENTRY_PRELOADED_PLUS_NODE_ON_DEMAND");
        }
        return new PreloadDecision(false, false, "AUTO", "default_minimal_runtime_state", "MIN_RUNTIME_STATE_PLUS_NODE_ON_DEMAND");
    }

    private boolean shouldAutoFallbackPreload(TaskRuntimeState taskState) {
        if (taskState == null) {
            return false;
        }
        return taskState == TaskRuntimeState.EXECUTING
                || taskState == TaskRuntimeState.WAITING_TOOL
                || taskState == TaskRuntimeState.WAITING_APPROVAL;
    }

    private record PreloadDecision(boolean preloadTaskMemory,
                                   boolean preloadRelationalMemory,
                                   String preloadMode,
                                   String reason,
                                   String memoryFetchMode) {
    }
}

