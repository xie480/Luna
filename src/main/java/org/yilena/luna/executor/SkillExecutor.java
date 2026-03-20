package org.yilena.luna.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.yilena.luna.constants.RocketMqConstant;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.RunMode;
import org.yilena.luna.mq.dto.SkillExecutionMessage;

import java.util.UUID;

/**
 * 技能執行器
 * 負責處理複合技能和異步任務
 * 【v2.1 重構】移除了審批邏輯，審批現由 Tool 層級的 ExecutionGate 統一處理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillExecutor {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 執行技能
     * @param skill 技能資源定義
     * @param argsJson 參數
     * @return 執行結果
     */
    public String execute(Resource skill, String argsJson) {
        log.info("正在調度技能: {}", skill.getName());

        // 1. 處理異步邏輯 (轉為 MQ 發送)
        if (RunMode.ASYNC.equals(skill.getRunMode())) {
            String taskId = UUID.randomUUID().toString();
            log.info("提交異步任務至 MQ, TaskId: {}", taskId);
            
            SkillExecutionMessage msg = SkillExecutionMessage.builder()
                    .taskId(taskId)
                    .resource(skill)
                    .argsJson(argsJson)
                    .build();
            
            rocketMQTemplate.convertAndSend(RocketMqConstant.TOPIC_SKILL_ASYNC, msg);

            return String.format("{\"status\":\"pending\", \"taskId\":\"%s\", \"message\":\"異步任務已提交，後台執行中\"}", taskId);
        }

        // 2. 同步執行 (此處簡化為直接返回成功，實際應調用具體的 Skill Bean)
        return String.format("{\"status\":\"success\", \"message\":\"技能 %s 執行完畢\", \"result\": \"模擬執行結果\"}", skill.getName());
    }
}
