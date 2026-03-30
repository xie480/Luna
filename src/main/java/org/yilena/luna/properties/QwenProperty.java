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
    private String apiKey;
    private String LunaId;
    private String SummaryId;
}
