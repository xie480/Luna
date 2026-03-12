package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gemini")
@Data
public class GeminiProperty {
    private String url;
    private String api;
    private String chatModelName;
    private String summaryModelName;
}
