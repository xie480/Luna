package org.yilena.luna.mq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.entity.Resource;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * SkillExecutionMessage ??
 */
public class SkillExecutionMessage implements Serializable {
    private String taskId; // 声明成员字段
    private Resource resource; // 声明成员字段
    private String argsJson; // 声明成员字段
} // 结束当前代码块
