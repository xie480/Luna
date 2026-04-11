package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Ollama 配置属性类，负责承载本地模型服务地址、模型名和超时设置。
 */
@Configuration
@ConfigurationProperties(prefix = "ollama")
@Data
public class OllamaProperty {

    /**
     * Ollama 服务基础地址。
     */
    private String baseUrl;

    /**
     * 默认调用的 Ollama 模型名。
     */
    private String model;

    /**
     * 请求超时时间，单位为毫秒。
     */
    private int timeoutMs = 60000;
}
