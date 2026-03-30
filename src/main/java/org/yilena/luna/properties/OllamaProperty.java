package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "ollama")
@Data
/**
 * OllamaProperty ??
 */
public class OllamaProperty {
    private String baseUrl; // 声明成员字段
    private String model; // 声明成员字段
    private int timeoutMs = 60000; // 声明成员字段
} // 结束当前代码块

