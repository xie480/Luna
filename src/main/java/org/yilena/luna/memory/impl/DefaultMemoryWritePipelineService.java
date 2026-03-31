package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.MemoryWriteMapper;
import org.yilena.luna.memory.MemoryWritePipelineService;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultMemoryWritePipelineService implements MemoryWritePipelineService {

    private final MemoryWriteMapper memoryWriteMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void writeAfterTurn(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        insertMessage(sessionId, "USER", userInput);
        insertMessage(sessionId, "ASSISTANT", assistantReply);
        updateSessionState(sessionId, contextPackage);
        upsertTaskWorkingMemory(sessionId, userInput, assistantReply, contextPackage);
        upsertRelationalWorkingMemory(sessionId, contextPackage);
        extractAndPersistSemanticFacts(sessionId, userInput);
        upsertRelationalLongTermMemory(sessionId, userInput, contextPackage);
        buildEpisodes(sessionId, userInput, assistantReply, contextPackage);
        reflectAndMineProcedures(sessionId, userInput, assistantReply, contextPackage);
        updateProcedureStatistics(userInput, contextPackage);
        refreshWorkingMemoryRegistry(sessionId);
    }

    private void insertMessage(String sessionId, String role, String content) {
        try {
            memoryWriteMapper.insertMessage(sessionId, role, content);
        } catch (Exception ignore) {
        }
    }

    private void updateSessionState(String sessionId, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationalState = contextPackage.getRelationalState();
        try {
            memoryWriteMapper.updateSessionState(
                    sessionId,
                    taskState != null ? taskState.name() : TaskRuntimeState.UNDERSTANDING.name(),
                    relationalState != null ? relationalState.name() : RelationalRuntimeState.LIGHT_CHAT.name()
            );
        } catch (Exception ignore) {
        }
    }

    private void upsertTaskWorkingMemory(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        List<String> constraints = extractTaskConstraints(lower);
        List<String> successCriteria = extractSuccessCriteria(lower);
        List<String> entities = extractKeyEntities(lower);
        List<String> questions = extractQuestions(userInput);
        List<String> risks = extractTaskRisks(lower);
        try {
            memoryWriteMapper.upsertTaskWorking(
                    sessionId,
                    userInput,
                    summarize(userInput, 260),
                    toJson(Map.of("source", "memory_write_pipeline")),
                    toJson(constraints),
                    toJson(successCriteria),
                    toJson(List.of()),
                    toJson(entities),
                    toJson(List.of()),
                    toJson(questions),
                    toJson(risks),
                    null,
                    null,
                    toJson(List.of()),
                    summarize(assistantReply, 260)
            );
            upsertTaskWorkingSlots(sessionId, constraints, successCriteria, entities, questions, risks);
        } catch (Exception ignore) {
        }
    }

    private void upsertTaskWorkingSlots(String sessionId,
                                        List<String> constraints,
                                        List<String> successCriteria,
                                        List<String> entities,
                                        List<String> questions,
                                        List<String> risks) {
        upsertSlot(sessionId, "constraints", "CONSTRAINT", constraints, 90);
        upsertSlot(sessionId, "success_criteria", "SUCCESS_CRITERIA", successCriteria, 90);
        upsertSlot(sessionId, "key_entities", "ENTITY", entities, 70);
        upsertSlot(sessionId, "unresolved_questions", "QUESTION", questions, 80);
        upsertSlot(sessionId, "risks", "RISK", risks, 85);
    }

    private void upsertSlot(String sessionId, String slotName, String slotType, Object value, int priority) {
        try {
            memoryWriteMapper.upsertTaskWorkingSlot(
                    sessionId,
                    slotName,
                    slotType,
                    toJson(value),
                    priority,
                    "MEMORY_WRITE_PIPELINE",
                    sessionId
            );
        } catch (Exception ignore) {
        }
    }

    private void upsertRelationalWorkingMemory(String sessionId, StructuredContextPackage contextPackage) {
        String relationalState = contextPackage != null && contextPackage.getRelationalState() != null
                ? contextPackage.getRelationalState().name()
                : RelationalRuntimeState.LIGHT_CHAT.name();
        try {
            memoryWriteMapper.upsertRelationalWorking(
                    sessionId,
                    relationalState,
                    "NEUTRAL",
                    0.65,
                    inferTone(relationalState),
                    "task_forward",
                    "solve_task",
                    toJson(List.of()),
                    toJson(List.of()),
                    toJson(List.of())
            );
        } catch (Exception ignore) {
        }
    }

    private String inferTone(String relationalState) {
        if ("EMOTIONAL_SUPPORT".equals(relationalState) || "FRAGILE_MOMENT".equals(relationalState)) {
            return "soft_and_calm";
        }
        if ("CELEBRATING".equals(relationalState)) {
            return "warm_and_positive";
        }
        return "clear_and_friendly";
    }

    private void extractAndPersistSemanticFacts(String sessionId, String userInput) {
        String text = userInput == null ? "" : userInput.trim();
        if (text.isEmpty()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "default", "prefer", "markdown", "format", "style", "偏好", "默认")) {
            insertTaskSemanticFact(sessionId, "PREFERENCE", "auto_extracted_task_pref", text, "USER_INPUT");
        }
        if (containsAny(lower, "do not call me", "don't lecture", "uncomfortable", "need support first", "别叫我", "先别给方案", "不喜欢说教")) {
            insertRelationalSemanticFact(sessionId, "BOUNDARY", "auto_extracted_relation_boundary", text, "USER_INPUT");
        }
    }

    private void upsertRelationalLongTermMemory(String sessionId, String userInput, StructuredContextPackage contextPackage) {
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        try {
            memoryWriteMapper.upsertRelationalProfile(
                    sessionId,
                    contextPackage != null && contextPackage.getRelationalState() != null ? contextPackage.getRelationalState().name() : "FAMILIARIZING",
                    "",
                    containsAny(lower, "简短", "直接") ? "concise_direct" : "clear_and_friendly",
                    containsAny(lower, "先别给方案", "先听我说") ? "listen_first" : "balanced",
                    "neutral",
                    "medium",
                    toJson(Map.of("source", "online_pipeline")),
                    toJson(Map.of("source", "online_pipeline")),
                    toJson(List.of()),
                    toJson(extractComfortTriggers(lower)),
                    toJson(extractNoGoPatterns(lower)),
                    0.65,
                    0.62
            );
        } catch (Exception ignore) {
        }

        try {
            memoryWriteMapper.upsertEmotionalBaseline(
                    sessionId,
                    containsAny(lower, "急", "快点") ? "urgent" : "neutral",
                    toJson(extractSignals(lower, "焦虑", "紧张", "来不及")),
                    toJson(extractSignals(lower, "很累", "没力气", "崩溃")),
                    toJson(extractSignals(lower, "难受", "低落", "失望")),
                    toJson(extractComfortTriggers(lower)),
                    toJson(List.of("small_steps")),
                    containsAny(lower, "崩溃", "撑不住") ? 0.55 : 0.72
            );
        } catch (Exception ignore) {
        }

        tryInsertBoundaryRule(sessionId, lower, "ADDRESS", "name_usage", "别叫我");
        tryInsertBoundaryRule(sessionId, lower, "EMOTIONAL", "avoid_preachy_tone", "不喜欢说教");
        tryInsertBoundaryRule(sessionId, lower, "PACE", "listen_before_advice", "先别给方案");
    }

    private void insertTaskSemanticFact(String sessionId, String factType, String factKey, String factValue, String sourceType) {
        try {
            memoryWriteMapper.insertTaskSemanticFact(sessionId, factType, factKey, factValue, sourceType, sessionId);
        } catch (Exception ignore) {
        }
    }

    private void insertRelationalSemanticFact(String sessionId, String factType, String factKey, String factValue, String sourceType) {
        try {
            memoryWriteMapper.insertRelationalSemanticFact(sessionId, factType, factKey, factValue, sourceType, sessionId);
        } catch (Exception ignore) {
        }
    }

    private void buildEpisodes(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationState = contextPackage.getRelationalState();

        if (taskState == TaskRuntimeState.COMPLETED || taskState == TaskRuntimeState.FAILED || taskState == TaskRuntimeState.REPORTING) {
            try {
                memoryWriteMapper.insertTaskEpisode(
                        sessionId,
                        taskState == TaskRuntimeState.FAILED ? "FAILURE" : "SUCCESS",
                        summarize(userInput, 96),
                        summarize(userInput, 300),
                        summarize(assistantReply, 400),
                        taskState.name(),
                        taskState == TaskRuntimeState.FAILED ? "needs_replan" : "successful_turn"
                );
                Long episodeId = memoryWriteMapper.selectLatestTaskEpisodeId(sessionId);
                writeEpisodeSteps(episodeId, userInput, assistantReply, taskState);
            } catch (Exception ignore) {
            }
        }

        if (relationState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationState == RelationalRuntimeState.REPAIRING
                || relationState == RelationalRuntimeState.CELEBRATING
                || relationState == RelationalRuntimeState.FRAGILE_MOMENT) {
            try {
                memoryWriteMapper.insertRelationalEpisode(
                        sessionId,
                        relationState == RelationalRuntimeState.CELEBRATING ? "CELEBRATION" : (relationState == RelationalRuntimeState.REPAIRING ? "REPAIR" : "COMFORT"),
                        summarize(userInput, 96),
                        summarize(assistantReply, 320),
                        relationState.name(),
                        relationState.name(),
                        inferTone(relationState.name())
                );
            } catch (Exception ignore) {
            }
        }
    }

    private void writeEpisodeSteps(Long episodeId, String userInput, String assistantReply, TaskRuntimeState taskState) {
        if (episodeId == null) {
            return;
        }
        insertEpisodeStep(episodeId, 1, "USER_INPUT", "User Request", summarize(userInput, 400), Map.of());
        insertEpisodeStep(episodeId, 2, "ASSISTANT_OUTPUT", "Assistant Reply", summarize(assistantReply, 500), Map.of("task_state", taskState.name()));
    }

    private void insertEpisodeStep(Long episodeId,
                                   int order,
                                   String stepType,
                                   String title,
                                   String content,
                                   Map<String, Object> payload) {
        try {
            memoryWriteMapper.insertTaskEpisodeStep(
                    episodeId,
                    order,
                    stepType,
                    title,
                    content,
                    toJson(payload)
            );
        } catch (Exception ignore) {
        }
    }

    private void reflectAndMineProcedures(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        ensureTaskExecutionProcedure();
        ensureRelationalSupportProcedure();

        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationState = contextPackage.getRelationalState();
        if (taskState == TaskRuntimeState.FAILED || taskState == TaskRuntimeState.REFLECTING) {
            writeTaskReflection(sessionId, userInput, assistantReply, taskState);
            ensureTaskRecoveryProcedure();
        }
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (relationState == RelationalRuntimeState.REPAIRING
                || containsAny(lower, "you don't get me", "offended", "uncomfortable", "not this way", "你没懂我", "被冒犯", "不舒服")) {
            writeRelationalReflection(sessionId, userInput, assistantReply);
            ensureRelationalRepairProcedure();
        }
    }

    private void updateProcedureStatistics(String userInput, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationState = contextPackage.getRelationalState();
        boolean taskSuccess = taskState == TaskRuntimeState.COMPLETED || taskState == TaskRuntimeState.REPORTING;
        boolean taskFailure = taskState == TaskRuntimeState.FAILED || taskState == TaskRuntimeState.REFLECTING;
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);

        try {
            memoryWriteMapper.updateTaskExecutionProcedureStats(taskSuccess ? 1 : 0, taskFailure ? 1 : 0);
            if (taskFailure) {
                memoryWriteMapper.incrementTaskFailureRecovery();
            }
        } catch (Exception ignore) {
        }

        boolean relationEngaged = relationState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationState == RelationalRuntimeState.FRAGILE_MOMENT
                || relationState == RelationalRuntimeState.REPAIRING
                || relationState == RelationalRuntimeState.CELEBRATING;
        if (!relationEngaged) {
            return;
        }
        boolean relationFailure = relationState == RelationalRuntimeState.REPAIRING
                || containsAny(lower, "you don't get me", "offended", "uncomfortable", "not this way", "你没懂我", "被冒犯", "不舒服");
        try {
            memoryWriteMapper.updateRelationalSupportProcedureStats(relationFailure ? 0 : 1, relationFailure ? 1 : 0);
            if (relationFailure) {
                memoryWriteMapper.incrementRelationalRepair();
            }
        } catch (Exception ignore) {
        }
    }

    private void writeTaskReflection(String sessionId, String userInput, String assistantReply, TaskRuntimeState taskState) {
        try {
            memoryWriteMapper.insertTaskReflection(
                    sessionId,
                    taskState.name(),
                    "task_state_trigger",
                    summarize(userInput, 220),
                    "execution_quality_risk",
                    summarize(assistantReply, 220)
            );
        } catch (Exception ignore) {
        }
    }

    private void writeRelationalReflection(String sessionId, String userInput, String assistantReply) {
        try {
            memoryWriteMapper.insertRelationalReflection(
                    sessionId,
                    summarize(userInput, 220),
                    "tone_or_understanding_gap",
                    summarize(assistantReply, 220)
            );
        } catch (Exception ignore) {
        }
    }

    private void ensureTaskExecutionProcedure() {
        try {
            memoryWriteMapper.ensureTaskExecutionProcedure();
        } catch (Exception ignore) {
        }
    }

    private void ensureTaskRecoveryProcedure() {
        try {
            memoryWriteMapper.ensureTaskRecoveryProcedure();
        } catch (Exception ignore) {
        }
    }

    private void ensureRelationalSupportProcedure() {
        try {
            memoryWriteMapper.ensureRelationalSupportProcedure();
        } catch (Exception ignore) {
        }
    }

    private void ensureRelationalRepairProcedure() {
        try {
            memoryWriteMapper.ensureRelationalRepairProcedure();
        } catch (Exception ignore) {
        }
    }

    private void refreshWorkingMemoryRegistry(String sessionId) {
        try {
            memoryWriteMapper.refreshTaskWorkingRegistry(sessionId);
            memoryWriteMapper.refreshRelationalWorkingRegistry(sessionId);
            memoryWriteMapper.refreshTaskSemanticRegistry(sessionId);
            memoryWriteMapper.refreshRelationalSemanticRegistry(sessionId);
            memoryWriteMapper.refreshTaskEpisodeRegistry(sessionId);
            memoryWriteMapper.refreshRelationalEpisodeRegistry(sessionId);
            memoryWriteMapper.refreshTaskProcedureRegistry(sessionId);
            memoryWriteMapper.refreshRelationalProcedureRegistry(sessionId);
            memoryWriteMapper.refreshRelationalProfileRegistry(sessionId);
            memoryWriteMapper.refreshEmotionalBaselineRegistry(sessionId);
            memoryWriteMapper.refreshBoundaryRuleRegistry(sessionId);
            memoryWriteMapper.upsertWorkingDerivedRelations(sessionId);
        } catch (Exception ignore) {
        }
    }

    private List<String> extractTaskConstraints(String lower) {
        return extractSignals(lower, "不能", "先不", "不要", "必须", "截止");
    }

    private List<String> extractSuccessCriteria(String lower) {
        return extractSignals(lower, "完成", "通过", "上线", "交付");
    }

    private List<String> extractTaskRisks(String lower) {
        return extractSignals(lower, "风险", "担心", "来不及", "失败");
    }

    private List<String> extractKeyEntities(String lower) {
        return extractSignals(lower, "q1", "q2", "国内", "海外", "产品", "用户");
    }

    private List<String> extractQuestions(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (text.contains("?") || text.contains("？") || text.contains("是否")) {
            return List.of(summarize(text, 160));
        }
        return List.of();
    }

    private List<String> extractComfortTriggers(String lower) {
        return extractSignals(lower, "陪我", "一起", "慢一点", "先听我说");
    }

    private List<String> extractNoGoPatterns(String lower) {
        return extractSignals(lower, "说教", "训我", "催促");
    }

    private List<String> extractSignals(String lower, String... words) {
        if (lower == null || lower.isBlank()) {
            return List.of();
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                out.add(word);
            }
        }
        return out;
    }

    private void tryInsertBoundaryRule(String sessionId, String lower, String ruleType, String ruleKey, String triggerWord) {
        if (!lower.contains(triggerWord.toLowerCase(Locale.ROOT))) {
            return;
        }
        try {
            memoryWriteMapper.insertRelationalBoundaryRule(sessionId, ruleType, ruleKey, triggerWord, 0.82, "USER_INPUT");
        } catch (Exception ignore) {
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception ignore) {
            return "[]";
        }
    }

    private String summarize(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }

    private boolean containsAny(String text, String... words) {
        if (text == null || words == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
