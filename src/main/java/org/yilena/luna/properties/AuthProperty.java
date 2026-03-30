package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "auth")
@Data
/**
 * AuthProperty ??
 */
public class AuthProperty {
    private String username;
    private String password;

    /**
     * JWT 签名密钥（生产环境请使用高强度随机字符串并妥善保管）
     */
    private String jwtSecret = "change-me-in-production";

    /**
     * JWT 过期时间（秒）
     */
    private long jwtExpireSeconds = 86400;
}
