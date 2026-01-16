package org.yilena.runa.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "qwen")
@Data
public class QwenProperty {
    private String apiKey;
    private String LunaId;
    private String SummaryId;
}
