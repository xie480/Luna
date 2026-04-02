package org.yilena.luna.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.service.SessionService;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final SessionRuntimeMapper sessionRuntimeMapper;

    @Override
    public void appendMessage(String keyPrefix, ChatMessage msg) {
        if (keyPrefix == null || keyPrefix.isBlank() || msg == null) {
            return;
        }
        try {
            sessionRuntimeMapper.insertConversationMessage(
                    keyPrefix,
                    toDbRole(msg.getRole()),
                    msg.getContent(),
                    toCreatedAt(msg.getTime())
            );
        } catch (Exception e) {
            log.warn("append message failed, sessionId={}, err={}", keyPrefix, e.getMessage());
        }
    }

    @Override
    public List<ChatMessage> getRecentMessages(String keyPrefix, boolean isOld) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> rows = sessionRuntimeMapper.selectConversationMessages(keyPrefix);
            List<ChatMessage> out = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                String role = str(row.get("role"));
                String content = str(row.get("content_text"));
                LocalTime time = toLocalTime(row.get("created_at"));
                out.add(new ChatMessage(fromDbRole(role), content, time));
            }
            return out;
        } catch (Exception e) {
            log.warn("read messages failed, sessionId={}, err={}", keyPrefix, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            sessionRuntimeMapper.deleteConversationUserAssistant(sessionId);
        } catch (Exception e) {
            log.warn("clear session failed, sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    @Override
    public void replaceHistoryWithSummary(String sessionId, String summary) {
        replaceHistoryWithSummary(sessionId, summary, "");
    }

    @Override
    public void replaceHistoryWithSummary(String sessionId, String narrativeSummary, String stateSnapshotText) {
        clearSession(sessionId);
        String narrative = narrativeSummary == null ? "" : narrativeSummary.trim();
        String snapshot = stateSnapshotText == null ? "" : stateSnapshotText.trim();
        if (!narrative.isBlank()) {
            appendMessage(sessionId, new ChatMessage(ChatMessage.Role.CONTEXT_SUMMARY, narrative, LocalTime.now()));
        }
        if (!snapshot.isBlank()) {
            appendMessage(sessionId, new ChatMessage(ChatMessage.Role.CONTEXT_SUMMARY, "[STATE_SNAPSHOT] " + snapshot, LocalTime.now()));
        }
    }

    private LocalDateTime toCreatedAt(LocalTime time) {
        LocalTime safeTime = time == null ? LocalTime.now() : time;
        return LocalDateTime.now()
                .withHour(safeTime.getHour())
                .withMinute(safeTime.getMinute())
                .withSecond(safeTime.getSecond());
    }

    private LocalTime toLocalTime(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalTime();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalTime();
        }
        return LocalTime.now();
    }

    private String toDbRole(ChatMessage.Role role) {
        if (role == null) {
            return "ASSISTANT";
        }
        return switch (role) {
            case USER -> "USER";
            case LUNA -> "ASSISTANT";
            case CONTEXT_SUMMARY -> "SUMMARY";
            case STARTUP -> "SYSTEM";
            case SHUTDOWN -> "SYSTEM";
        };
    }

    private ChatMessage.Role fromDbRole(String role) {
        if (role == null || role.isBlank()) {
            return ChatMessage.Role.LUNA;
        }
        return switch (role) {
            case "USER" -> ChatMessage.Role.USER;
            case "ASSISTANT" -> ChatMessage.Role.LUNA;
            case "SUMMARY" -> ChatMessage.Role.CONTEXT_SUMMARY;
            default -> ChatMessage.Role.LUNA;
        };
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
