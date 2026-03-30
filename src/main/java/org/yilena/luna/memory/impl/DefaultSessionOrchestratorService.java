package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.SessionOrchestratorService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DefaultSessionOrchestratorService implements SessionOrchestratorService {

    private final SessionRuntimeMapper sessionRuntimeMapper;
    private final ContextCompilerService contextCompilerService;

    @Override
    public OrchestrationDecision onUserInput(String sessionId, String userInput) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? "default-session" : sessionId;
        Long principalId = principalIdOf(normalizedSessionId);

        TaskRuntimeState previousTaskState = getCurrentTaskState(normalizedSessionId);
        RelationalRuntimeState previousRelationalState = getCurrentRelationalState(normalizedSessionId);

        TaskRuntimeState nextTaskState = inferTaskState(previousTaskState, userInput);
        RelationalRuntimeState nextRelationalState = inferRelationalState(previousRelationalState, userInput);

        upsertPrincipal(principalId, normalizedSessionId);
        upsertSession(normalizedSessionId, principalId, nextTaskState, nextRelationalState, userInput);

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
        return parseState(sessionRuntimeMapper.selectTaskState(sessionId), TaskRuntimeState.IDLE);
    }

    private RelationalRuntimeState getCurrentRelationalState(String sessionId) {
        return parseState(sessionRuntimeMapper.selectRelationalState(sessionId), RelationalRuntimeState.COLD_START);
    }

    @SuppressWarnings("unchecked")
    private <T extends Enum<T>> T parseState(String stateText, T fallback) {
        try {
            if (stateText == null || stateText.isBlank()) {
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

    private void upsertSession(String sessionId,
                               Long principalId,
                               TaskRuntimeState taskState,
                               RelationalRuntimeState relationalState,
                               String userInput) {
        try {
            sessionRuntimeMapper.upsertSession(sessionId, principalId, taskState.name(), relationalState.name(), userInput);
        } catch (Exception ignore) {
        }
    }

    private void upsertPrincipal(Long principalId, String sessionId) {
        if (principalId == null) {
            return;
        }
        try {
            sessionRuntimeMapper.upsertPrincipal(principalId, sessionId);
        } catch (Exception ignore) {
        }
    }

    private Long principalIdOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return sessionRuntimeMapper.selectPrincipalId(sessionId);
        } catch (Exception ignore) {
            return null;
        }
    }

    private void insertTransition(String sessionId, String domain, String fromState, String toState, String userInput) {
        try {
            sessionRuntimeMapper.insertTransition(sessionId, domain, fromState, toState, sessionId, userInput);
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
