package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 通义千问配置属性类，负责承载不同业务场景下使用的模型标识和访问密钥。
 */
@Configuration
@ConfigurationProperties(prefix = "qwen")
@Data
public class QwenProperty {

    /**
     * 通义千问访问密钥。
     */
    private String apiKey;

    /**
     * Luna 主流程使用的模型标识。
     */
    private String LunaId;

    /**
     * 摘要流程使用的模型标识。
     */
    private String SummaryId;
}
