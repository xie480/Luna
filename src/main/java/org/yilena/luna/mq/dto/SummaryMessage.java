package org.yilena.luna.mq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 上下文摘要消息体，负责承载异步摘要编排所需的会话信息和对话片段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryMessage implements Serializable {

    /**
     * 会话键，用于定位需要汇总的上下文。
     */
    private String sessionKey;

    /**
     * 记忆片段列表。
     */
    private List<String> memorySnippets;

    /**
     * 当前轮用户输入。
     */
    private String userInput;

    /**
     * 当前轮助手回复。
     */
    private String assistantReply;

    /**
     * 触发摘要的来源标识。
     */
    private String triggerSource;
}
