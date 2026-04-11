package org.yilena.luna.mq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.entity.Resource;

import java.io.Serializable;

/**
 * 工作流异步执行消息体，负责承载任务标识、目标资源和调用参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionMessage implements Serializable {

    /**
     * 异步任务标识。
     */
    private String taskId;

    /**
     * 需要执行的工作流或技能资源。
     */
    private Resource resource;

    /**
     * 资源执行参数 JSON。
     */
    private String argsJson;
}
