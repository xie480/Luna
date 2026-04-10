package org.yilena.luna.rag.models;

import lombok.Builder;
import lombok.Value;

/**
 * 该模型用于表示检索阶段透传的单条对话消息，保留角色与内容信息供查询改写和路由使用。
 */
@Value
@Builder
public class ConversationMessage {
    /**
     * 消息发送角色，例如 user、assistant 或 system。
     */
    String role;
    /**
     * 消息正文内容。
     */
    String content;
}
