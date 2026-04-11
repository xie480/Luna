package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 向量模型配置属性类，负责承载 embedding 脚本执行所需的路径信息。
 */
@Configuration
@ConfigurationProperties(prefix = "embedding")
@Data
public class EmbeddingProperty {

    /**
     * Python 解释器路径。
     */
    private String pythonPath;

    /**
     * embedding 脚本路径。
     */
    private String scriptPath;

    /**
     * 向量模型文件路径。
     */
    private String modelPath;
}
