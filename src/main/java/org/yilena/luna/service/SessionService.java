package org.yilena.luna.service;

import org.yilena.luna.entity.ChatMessage;

import java.util.List;

/**
 * 會話管理服務接口
 * 負責管理 Redis 中的聊天上下文
 */
public interface SessionService {

    /**
     * 追加消息到會話歷史
     * @param keyPrefix 會話標識前綴 (通常是日期)
     * @param msg 消息實體
     */
    void appendMessage(String keyPrefix, ChatMessage msg);

    /**
     * 獲取最近的會話消息
     * @param keyPrefix 會話標識前綴
     * @param isOld 是否獲取舊數據 (具體邏輯由實現決定)
     * @return 消息列表
     */
    List<ChatMessage> getRecentMessages(String keyPrefix, boolean isOld);

    /**
     * 清理指定會話
     * @param sessionId 會話ID
     */
    void clearSession(String sessionId);

    /**
     * 將歷史記錄替換為摘要
     * @param sessionId 會話ID
     * @param summary 摘要內容
     */
    void replaceHistoryWithSummary(String sessionId, String summary);
}
