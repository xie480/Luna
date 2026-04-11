package org.yilena.luna.service;

import org.yilena.luna.entity.ChatMessage;

import java.util.List;

/**
 * 会话管理服务接口，负责维护 Redis 中的聊天上下文与摘要结果。
 * 该接口用于支撑多轮对话的追加、读取、清理与历史压缩流程。
 */
public interface SessionService {

    /**
     * 向指定会话的消息列表追加一条聊天消息。
     * @param keyPrefix 会话缓存键前缀
     * @param msg 待追加的消息实体
     */
    void appendMessage(String keyPrefix, ChatMessage msg);

    /**
     * 获取指定会话最近的聊天消息列表。
     * @param keyPrefix 会话缓存键前缀
     * @param isOld 是否读取旧数据分支，具体逻辑由实现决定
     * @return 最近的消息列表
     */
    List<ChatMessage> getRecentMessages(String keyPrefix, boolean isOld);

    /**
     * 清理指定会话的上下文数据。
     * @param sessionId 会话 ID
     */
    void clearSession(String sessionId);

    /**
     * 将历史消息压缩为单段摘要，降低上下文长度。
     * @param sessionId 会话 ID
     * @param summary 摘要内容
     */
    void replaceHistoryWithSummary(String sessionId, String summary);

    /**
     * 将历史消息替换为双摘要结构，分别保存叙事摘要和状态快照。
     * @param sessionId 会话 ID
     * @param narrativeSummary 叙事摘要
     * @param stateSnapshotText 状态快照文本
     */
    void replaceHistoryWithSummary(String sessionId, String narrativeSummary, String stateSnapshotText);
}
