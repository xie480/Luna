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
/**
 * 会话服务实现类，负责对话消息的持久化、读取、清理以及摘要替换。
 */
public class SessionServiceImpl implements SessionService {

    /**
     * 会话运行态 Mapper，负责读写对话消息记录。
     */
    private final SessionRuntimeMapper sessionRuntimeMapper;

    @Override
    /**
     * 向指定会话追加一条消息记录。
     */
    public void appendMessage(String keyPrefix, ChatMessage msg) {
        if (keyPrefix == null || keyPrefix.isBlank() || msg == null) {
            return;
        }
        try {
            /**
             * 将业务消息转换为数据库角色和时间格式后写入会话消息表。
             */
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
    /**
     * 读取指定会话的最近消息列表，并还原为业务层使用的消息对象。
     */
    public List<ChatMessage> getRecentMessages(String keyPrefix, boolean isOld) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return Collections.emptyList();
        }
        try {
            /**
             * 从数据库读取消息后逐条转换为业务消息对象，统一角色和值类型。
             */
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
    /**
     * 清理指定会话中的用户和助手消息，为摘要重写等场景做准备。
     */
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
    /**
     * 用叙事摘要替换原有历史消息，不携带状态快照。
     */
    public void replaceHistoryWithSummary(String sessionId, String summary) {
        replaceHistoryWithSummary(sessionId, summary, "");
    }

    @Override
    /**
     * 用叙事摘要和状态快照替换原有历史消息，压缩会话上下文长度。
     */
    public void replaceHistoryWithSummary(String sessionId, String narrativeSummary, String stateSnapshotText) {
        /**
         * 先清空原始会话消息，再按需回写摘要和状态快照，确保上下文压缩结果成为新的历史起点。
         */
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

    /**
     * 将业务层仅包含时分秒的时间补全为当前日期的数据库时间戳。
     */
    private LocalDateTime toCreatedAt(LocalTime time) {
        LocalTime safeTime = time == null ? LocalTime.now() : time;
        return LocalDateTime.now()
                .withHour(safeTime.getHour())
                .withMinute(safeTime.getMinute())
                .withSecond(safeTime.getSecond());
    }

    /**
     * 将数据库时间字段转换为会话消息使用的本地时间。
     */
    private LocalTime toLocalTime(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalTime();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalTime();
        }
        return LocalTime.now();
    }

    /**
     * 将业务层角色转换为数据库存储的角色编码。
     */
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

    /**
     * 将数据库角色编码转换回业务层角色枚举。
     */
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

    /**
     * 将任意对象安全转换为字符串，避免空值判断重复出现。
     */
    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
