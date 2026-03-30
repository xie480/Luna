package org.yilena.luna.mq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * KnowledgeBaseMessage ??
 */
public class KnowledgeBaseMessage implements Serializable {
    private String title; // 声明成员字段
    private String content; // 声明成员字段
    private String sourceType; // 存儲枚舉的 name()
    private String sourcePath; // 声明成员字段
} // 结束当前代码块
