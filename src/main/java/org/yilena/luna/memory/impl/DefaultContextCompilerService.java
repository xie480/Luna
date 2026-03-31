package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.CapabilityMapper;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.ResponseSynthesizerService;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.memory.RuntimeRetriever;
import org.yilena.luna.memory.SocialReasonerService;
import org.yilena.luna.memory.TaskMemoryRetriever;
import org.yilena.luna.memory.model.StructuredContextPackage;

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
    private final SocialReasonerService socialReasonerService;
    private final ResponseSynthesizerService responseSynthesizerService;
    private final CapabilityMapper capabilityMapper;

    @Override
    public StructuredContextPackage compile(String sessionId,
                                            String userInput,
                                            TaskRuntimeState taskState,
                                            RelationalRuntimeState relationalState) {
        Map<String, Object> runtime = runtimeRetriever.retrieve(sessionId);
        Map<String, Object> taskContext = taskMemoryRetriever.retrieve(sessionId, userInput);
        Map<String, Object> relationalContext = relationalMemoryRetriever.retrieve(sessionId, userInput);

        List<Map<String, Object>> recentMessages = safeList(runtime.get("recent_messages"));
        List<Map<String, Object>> capabilities = queryCapabilities();
        Map<String, Object> socialDraft = socialReasonerService.buildRelationalDraft(
                sessionId,
                userInput,
                relationalState,
                relationalContext
        );
        Map<String, Object> synthesisPolicy = responseSynthesizerService.buildSynthesisPolicy(
                taskState,
                relationalState,
                taskContext,
                relationalContext,
                socialDraft
        );
        Map<String, Object> promptPolicy = buildPromptPolicy(taskState, relationalState, socialDraft, synthesisPolicy);
        Map<String, Integer> budget = buildTokenBudget(taskState, relationalState);

        return StructuredContextPackage.builder()
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
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : Collections.emptyList();
    }

    private List<Map<String, Object>> queryCapabilities() {
        try {
            capabilityMapper.syncToolsIntoRegistry();
            capabilityMapper.syncPromptsIntoRegistry();
            capabilityMapper.syncResourcesIntoRegistry();
            capabilityMapper.syncWorkflowsIntoRegistry();
            return capabilityMapper.selectTopCapabilities();
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> buildPromptPolicy(TaskRuntimeState taskState,
                                                  RelationalRuntimeState relationalState,
                                                  Map<String, Object> socialDraft,
                                                  Map<String, Object> synthesisPolicy) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("task_mode", taskState.name());
        policy.put("social_mode", relationalState.name());
        policy.put("planner_enabled", taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING);
        policy.put("executor_enabled", taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.REPORTING);
        policy.put("social_reasoner_enabled", true);
        policy.put("synthesis_mode", "TASK_SOCIAL_MERGE");
        policy.put("social_draft", socialDraft == null ? Map.of() : socialDraft);
        policy.put("response_synthesis", synthesisPolicy == null ? Map.of() : synthesisPolicy);
        return policy;
    }

    private Map<String, Integer> buildTokenBudget(TaskRuntimeState taskState, RelationalRuntimeState relationalState) {
        Map<String, Integer> budget = new HashMap<>();
        if (relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT || relationalState == RelationalRuntimeState.FRAGILE_MOMENT) {
            budget.put("runtime", 500);
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
}
