package org.yilena.luna.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 认证配置属性类，负责承载登录账号、JWT 密钥和过期时间等鉴权配置。
 */
@Configuration
@ConfigurationProperties(prefix = "auth")
@Data
public class AuthProperty {

    /**
     * 基础认证用户名。
     */
    private String username;

    /**
     * 基础认证密码。
     */
    private String password;

    /**
     * JWT 签名密钥，生产环境应替换为安全随机字符串。
     */
    private String jwtSecret = "change-me-in-production";

    /**
     * JWT 过期时间，单位为秒。
     */
    private long jwtExpireSeconds = 86400;
}
