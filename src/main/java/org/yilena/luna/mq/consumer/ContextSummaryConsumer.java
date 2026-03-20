package org.yilena.luna.mq.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.mq.dto.SummaryMessage;
import org.yilena.luna.prompt.PromptAssembler;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_SUMMARY, consumerGroup = RocketMqConstant.GROUP_SUMMARY)
public class ContextSummaryConsumer implements RocketMQListener<SummaryMessage> {

    private final PromptAssembler promptAssembler;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final SessionService sessionService;

    @Override
    public void onMessage(SummaryMessage msg) {
        String sessionKey = msg.getSessionKey();
        List<String> memorySnippets = msg.getMemorySnippets();

        log.info("MQ 消費: 開始執行上下文壓縮，sessionKey={}", sessionKey);

        try {
            String summaryPrompt = promptAssembler.buildSummaryPrompt(memorySnippets);
            if (summaryPrompt.isBlank()) {
                log.info("上下文壓縮：沒有足夠的 memory 片段可供壓縮，sessionKey={}", sessionKey);
                return;
            }

            // 調用摘要模型
            LlmRequest request = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(geminiProperty.getMid().getModelName())
                    .messages(List.of(LlmMessage.user(summaryPrompt)))
                    .build();

            LlmResponse response = llmClientUtil.generate(request);
            String text = response != null ? response.getContent() : null;

            if (text != null && !text.isBlank() && text.length() >= 10) {
                sessionService.replaceHistoryWithSummary(sessionKey, text);
                log.info("MQ 消費: 上下文壓縮完成，sessionKey={}", sessionKey);
            } else {
                log.error("上下文壓縮失敗：模型返回為空或過短，sessionKey={}", sessionKey);
            }
        } catch (Exception ex) {
            log.error("上下文壓縮發生異常，sessionKey={}, 錯誤信息={}", sessionKey, ex.getMessage(), ex);
        }
    }
}
