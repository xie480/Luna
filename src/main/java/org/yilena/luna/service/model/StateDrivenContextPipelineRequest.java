package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
/**
 * 状态驱动上下文流水线请求模型，负责描述一次由特定触发源发起的回合处理请求，
 * 用于把触发来源和回合请求统一传入状态驱动流水线。
 */
public class StateDrivenContextPipelineRequest {
    /**
     * 当前会话标识。
     */
    String sessionId;
    /**
     * 触发本次流水线的来源标识。
     */
    String triggerSource;
    /**
     * 具体的单轮流水线请求。
     */
    RoundPipelineRequest roundPipelineRequest;
}
