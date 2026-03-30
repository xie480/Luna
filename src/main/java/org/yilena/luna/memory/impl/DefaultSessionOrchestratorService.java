package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.SessionOrchestratorService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultSessionOrchestratorService implements SessionOrchestratorService {

    private final JdbcTemplate jdbcTemplate;
    private final ContextCompilerService contextCompilerService;

    @Override
    public OrchestrationDecision onUserInput(String sessionId, String userInput) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? "default-session" : sessionId;
        String traceId = UUID.randomUUID().toString();

        TaskRuntimeState previousTaskState = getCurrentTaskState(normalizedSessionId);
        RelationalRuntimeState previousRelationalState = getCurrentRelationalState(normalizedSessionId);

        TaskRuntimeState nextTaskState = inferTaskState(previousTaskState, userInput);
        RelationalRuntimeState nextRelationalState = inferRelationalState(previousRelationalState, userInput);

        upsertSession(normalizedSessionId, nextTaskState, nextRelationalState, userInput);
        insertEvent(normalizedSessionId, userInput, traceId);

        if (previousTaskState != nextTaskState) {
            insertTransition(normalizedSessionId, "TASK", previousTaskState.name(), nextTaskState.name(), userInput);
        }
        if (previousRelationalState != nextRelationalState) {
            insertTransition(normalizedSessionId, "RELATION", previousRelationalState.name(), nextRelationalState.name(), userInput);
        }

        StructuredContextPackage contextPackage = contextCompilerService.compile(
                normalizedSessionId,
                userInput,
                nextTaskState,
                nextRelationalState
        );
        return OrchestrationDecision.builder()
                .sessionId(normalizedSessionId)
                .taskState(nextTaskState)
                .relationalState(nextRelationalState)
                .contextPackage(contextPackage)
                .build();
    }

    private TaskRuntimeState getCurrentTaskState(String sessionId) {
        return readState(
                "select task_state from agent_session where session_id = ?",
                sessionId,
                TaskRuntimeState.IDLE
        );
    }

    private RelationalRuntimeState getCurrentRelationalState(String sessionId) {
        return readState(
                "select relational_state from agent_session where session_id = ?",
                sessionId,
                RelationalRuntimeState.COLD_START
        );
    }

    @SuppressWarnings("unchecked")
    private <T extends Enum<T>> T readState(String sql, String sessionId, T fallback) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, sessionId);
            if (rows.isEmpty()) {
                return fallback;
            }
            Object raw = rows.get(0).values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (!(raw instanceof String stateText) || stateText.isBlank()) {
                return fallback;
            }
            return (T) Enum.valueOf(fallback.getDeclaringClass(), stateText.toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private TaskRuntimeState inferTaskState(TaskRuntimeState previous, String input) {
        String text = safeLower(input);
        if (containsAny(text, "计划", "规划", "plan", "roadmap", "方案")) {
            return TaskRuntimeState.PLANNING;
        }
        if (containsAny(text, "执行", "实现", "修复", "run", "build", "写代码")) {
            return TaskRuntimeState.EXECUTING;
        }
        if (containsAny(text, "总结", "汇报", "报告", "report", "复盘")) {
            return TaskRuntimeState.REPORTING;
        }
        if (containsAny(text, "失败", "报错", "重试", "replan", "反思")) {
            return TaskRuntimeState.REFLECTING;
        }
        if (previous == TaskRuntimeState.EXECUTING && containsAny(text, "等一下", "暂停", "stop")) {
            return TaskRuntimeState.WAITING_USER;
        }
        return TaskRuntimeState.UNDERSTANDING;
    }

    private RelationalRuntimeState inferRelationalState(RelationalRuntimeState previous, String input) {
        String text = safeLower(input);
        if (containsAny(text, "撑不住", "焦虑", "崩溃", "很累", "难受", "烦", "低落")) {
            return RelationalRuntimeState.EMOTIONAL_SUPPORT;
        }
        if (containsAny(text, "没懂我", "误会", "冒犯", "不舒服")) {
            return RelationalRuntimeState.REPAIRING;
        }
        if (containsAny(text, "庆祝", "开心", "太好了", "成功了")) {
            return RelationalRuntimeState.CELEBRATING;
        }
        if (previous == RelationalRuntimeState.COLD_START) {
            return RelationalRuntimeState.FAMILIARIZING;
        }
        if (previous == RelationalRuntimeState.FAMILIARIZING) {
            return RelationalRuntimeState.TRUST_BUILDING;
        }
        return RelationalRuntimeState.LIGHT_CHAT;
    }

    private void upsertSession(String sessionId, TaskRuntimeState taskState, RelationalRuntimeState relationalState, String userInput) {
        try {
            jdbcTemplate.update(
                    "insert into agent_session(session_id, session_type, task_state, relational_state, current_goal, " +
                            "last_user_message_at, metadata_json, created_at, updated_at) " +
                            "values (?, 'HYBRID', ?, ?, ?, current_timestamp, jsonb_build_object('source','session_orchestrator'), current_timestamp, current_timestamp) " +
                            "on conflict (session_id) do update set " +
                            "task_state = excluded.task_state, relational_state = excluded.relational_state, " +
                            "current_goal = excluded.current_goal, last_user_message_at = current_timestamp, updated_at = current_timestamp",
                    sessionId, taskState.name(), relationalState.name(), userInput
            );
        } catch (Exception ignore) {
        }
    }

    private void insertEvent(String sessionId, String userInput, String traceId) {
        try {
            jdbcTemplate.update(
                    "insert into event_inbox(session_id, event_type, payload_json, status, trace_id, created_at, updated_at) " +
                            "values (?, 'USER_INPUT', jsonb_build_object('text', ?), 'PROCESSED', ?, current_timestamp, current_timestamp)",
                    sessionId, userInput, traceId
            );
        } catch (Exception ignore) {
        }
    }

    private void insertTransition(String sessionId, String domain, String fromState, String toState, String userInput) {
        try {
            jdbcTemplate.update(
                    "insert into state_transition_log(session_id, state_domain, from_state, to_state, trigger_type, trigger_ref, reason, payload_json, created_at) " +
                            "values (?, ?, ?, ?, 'USER_INPUT', ?, 'state_update', jsonb_build_object('text', ?), current_timestamp)",
                    sessionId, domain, fromState, toState, sessionId, userInput
            );
        } catch (Exception ignore) {
        }
    }

    private String safeLower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
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
