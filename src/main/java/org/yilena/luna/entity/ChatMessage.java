package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
/**
 * 对话消息实体，负责描述单条对话记录的角色、内容和发生时间。
 */
public class ChatMessage {

    /**
     * 对话角色枚举，用于区分消息在会话链路中的来源。
     */
    public enum Role {
        /**
         * 用户输入的普通对话消息。
         */
        USER,
        /**
         * Luna 输出的普通回复消息。
         */
        LUNA,
        /**
         * 会话上下文压缩后生成的摘要消息。
         */
        CONTEXT_SUMMARY,
        /**
         * 系统启动阶段生成的欢迎或初始化消息。
         */
        STARTUP,
        /**
         * 系统关闭阶段生成的收尾消息。
         */
        SHUTDOWN
    }

    /**
     * 当前消息的角色类型。
     */
    private Role role;
    /**
     * 当前消息的文本内容。
     */
    private String content;
    /**
     * 当前消息发生的本地时间，用于会话历史展示。
     */
    private LocalTime time;
}
