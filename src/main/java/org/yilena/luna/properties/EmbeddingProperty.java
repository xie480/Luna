package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "embedding")
@Data
public class EmbeddingProperty {
    private String pythonPath;
    private String scriptPath;
    private String modelPath;
}
