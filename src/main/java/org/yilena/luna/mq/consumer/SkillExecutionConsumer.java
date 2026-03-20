package org.yilena.luna.mq.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.executor.ReflectionToolExecutor;
import org.yilena.luna.mq.dto.SkillExecutionMessage;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = RocketMqConstant.TOPIC_SKILL_ASYNC, consumerGroup = RocketMqConstant.GROUP_SKILL_ASYNC)
public class SkillExecutionConsumer implements RocketMQListener<SkillExecutionMessage> {

    private final ReflectionToolExecutor reflectionToolExecutor;

    @Override
    public void onMessage(SkillExecutionMessage msg) {
        log.info("MQ 消費: 開始執行異步技能任務, TaskId: {}, Skill: {}", msg.getTaskId(), msg.getResource().getName());
        
        try {
            // 調用反射執行器執行具體邏輯
            // 注意：異步任務通常不需要返回值給前端，或者需要將結果寫入 Task 表
            // 這裡演示執行過程
            String result = reflectionToolExecutor.executeInternal(
                    msg.getResource().getBeanName(), 
                    msg.getResource().getMethodName(), 
                    msg.getArgsJson()
            );
            
            log.info("異步技能任務執行完畢, TaskId: {}, Result: {}", msg.getTaskId(), result);
            
            // TODO: 在此處可以更新數據庫中的 Task 狀態為 COMPLETED，並保存 result
            
        } catch (Exception e) {
            log.error("異步技能任務執行失敗, TaskId: {}", msg.getTaskId(), e);
            // TODO: 更新 Task 狀態為 FAILED
        }
    }
}
