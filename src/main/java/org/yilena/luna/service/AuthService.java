package org.yilena.luna.service;

/**
 * 认证服务接口，负责定义登录、登出和令牌解析校验能力。
 */
public interface AuthService {

    /**
     * 根据用户名和密码执行登录。
     */
    String login(String username, String password);

    /**
     * 注销指定令牌。
     */
    void logout(String token);

    /**
     * 校验令牌是否合法且未过期。
     */
    boolean validateToken(String token);

    /**
     * 从令牌中提取会话标识。
     */
    String extractJti(String token);

    /**
     * 从令牌中提取主体标识。
     */
    String extractSubject(String token);
}
