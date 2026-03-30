package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gemini")
@Data
/**
 * GeminiProperty ??
 */
public class GeminiProperty {
    
    private ModelConfig small;
    private ModelConfig mid;
    private ModelConfig big;
    private ModelConfig flash;
    /**
     * 新增：用于普通对话场景的大模型
     */
    private ModelConfig chat;
    /**
     * 新增：用于代码与计划编排场景的大模型
     */
    private ModelConfig code;

    @Data
    /**
     * ModelConfig ??
     */
    public static class ModelConfig {
        private String url;
        private String apiKey;
        private String modelName;
    }
}
