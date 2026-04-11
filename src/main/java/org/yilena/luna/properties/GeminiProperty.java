package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini 配置属性类，负责承载不同规格模型在各类场景下的接入参数。
 */
@Configuration
@ConfigurationProperties(prefix = "gemini")
@Data
public class GeminiProperty {

    /**
     * 轻量模型配置。
     */
    private ModelConfig small;

    /**
     * 中等规格模型配置。
     */
    private ModelConfig mid;

    /**
     * 大规格模型配置。
     */
    private ModelConfig big;

    /**
     * Flash 系列模型配置。
     */
    private ModelConfig flash;

    /**
     * 面向通用对话场景的模型配置。
     */
    private ModelConfig chat;

    /**
     * 面向代码与规划场景的模型配置。
     */
    private ModelConfig code;

    /**
     * 单个 Gemini 模型的接入配置，负责描述接口地址、密钥和模型名。
     */
    @Data
    public static class ModelConfig {

        /**
         * 模型服务地址。
         */
        private String url;

        /**
         * 模型访问密钥。
         */
        private String apiKey;

        /**
         * 模型名称。
         */
        private String modelName;
    }
}
