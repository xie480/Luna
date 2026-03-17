package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gemini")
@Data
public class GeminiProperty {
    
    private ModelConfig small;
    private ModelConfig mid;
    private ModelConfig big;
    private ModelConfig flash;

    @Data
    public static class ModelConfig {
        private String url;
        private String apiKey;
        private String modelName;
    }
}
