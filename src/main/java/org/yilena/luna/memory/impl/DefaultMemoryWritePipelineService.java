package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.MemoryWriteMapper;
import org.yilena.luna.memory.MemoryWritePipelineService;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DefaultMemoryWritePipelineService implements MemoryWritePipelineService {

    private final MemoryWriteMapper memoryWriteMapper;

    @Override
    public void writeAfterTurn(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        insertMessage(sessionId, "USER", userInput);
        insertMessage(sessionId, "ASSISTANT", assistantReply);
        updateSessionState(sessionId, contextPackage);
        upsertTaskWorkingMemory(sessionId, userInput);
        upsertRelationalWorkingMemory(sessionId, contextPackage);
        extractAndPersistSemanticFacts(sessionId, userInput);
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

    private void upsertTaskWorkingMemory(String sessionId, String userInput) {
        try {
            memoryWriteMapper.upsertTaskWorking(sessionId, userInput, userInput);
        } catch (Exception ignore) {
        }
    }

    private void upsertRelationalWorkingMemory(String sessionId, StructuredContextPackage contextPackage) {
        String relationalState = contextPackage != null && contextPackage.getRelationalState() != null
                ? contextPackage.getRelationalState().name()
                : RelationalRuntimeState.LIGHT_CHAT.name();
        try {
            memoryWriteMapper.upsertRelationalWorking(sessionId, relationalState, inferTone(relationalState));
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
        if (containsAny(lower, "default", "prefer", "markdown", "format", "style")) {
            insertTaskSemanticFact(sessionId, "PREFERENCE", "auto_extracted_task_pref", text, "USER_INPUT");
        }
        if (containsAny(lower, "do not call me", "don't lecture", "uncomfortable", "need support first")) {
            insertRelationalSemanticFact(sessionId, "BOUNDARY", "auto_extracted_relation_boundary", text, "USER_INPUT");
        }
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
                || containsAny(lower, "you don't get me", "offended", "uncomfortable", "not this way")) {
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
                || containsAny(lower, "you don't get me", "offended", "uncomfortable", "not this way");
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
        } catch (Exception ignore) {
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
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
