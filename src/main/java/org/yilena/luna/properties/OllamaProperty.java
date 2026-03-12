package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "ollama")
@Data
public class OllamaProperty {
    private String baseUrl;
    private String model;
    private int timeoutMs = 60000;
}

