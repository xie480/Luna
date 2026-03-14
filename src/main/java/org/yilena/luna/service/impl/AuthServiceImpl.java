package org.yilena.luna.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.exception.AuthException;
import org.yilena.luna.properties.AuthProperty;
import org.yilena.luna.service.AuthService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthProperty authProperty;

    /**
     * 内存 Token 存储，服务重启后自动清空
     */
    private final Map<String, Boolean> tokenStore = new ConcurrentHashMap<>();

    @Override
    public String login(String username, String password) {
        if (!authProperty.getUsername().equals(username) ||
                !authProperty.getPassword().equals(password)) {
            throw new AuthException("用户名或密码错误");
        }
        String token = UUID.randomUUID().toString();
        tokenStore.put(token, true);
        log.info("用户 {} 登录成功，生成 Token: {}", username, token);
        return token;
    }

    @Override
    public boolean validateToken(String token) {
        if (token == null) return false;
        return tokenStore.containsKey(token);
    }

    @Override
    public void logout(String token) {
        tokenStore.remove(token);
    }
}
