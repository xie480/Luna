package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "qwen")
@Data
/**
 * QwenProperty ??
 */
public class QwenProperty {
    private String apiKey; // 声明成员字段
    private String LunaId; // 声明成员字段
    private String SummaryId; // 声明成员字段
} // 结束当前代码块
