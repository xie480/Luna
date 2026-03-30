package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "embedding")
@Data
/**
 * EmbeddingProperty ??
 */
public class EmbeddingProperty {
    private String pythonPath; // 声明成员字段
    private String scriptPath; // 声明成员字段
    private String modelPath; // 声明成员字段
} // 结束当前代码块
