package org.yilena.luna.service;

public interface AuthService {
    String login(String username, String password);

    void logout(String token);

    boolean validateToken(String token);

    /**
     * 从 JWT 中提取唯一会话标识 jti
     * @param token Authorization 头或原始 token
     * @return jti；若 token 非法或过期则返回 null
     */
    String extractJti(String token);
}
