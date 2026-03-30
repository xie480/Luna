package org.yilena.luna.mq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * SummaryMessage ??
 */
public class SummaryMessage implements Serializable {
    private String sessionKey; // 声明成员字段
    private List<String> memorySnippets; // 声明成员字段
} // 结束当前代码块
