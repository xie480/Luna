package org.yilena.runa.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yilena.runa.properties.AuthProperty;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    @Autowired
    private AuthProperty authProperty;

    /**
     * 内存 Token 存储，服务重启后自动清空
     */
    private final Map<String, Boolean> tokenStore = new ConcurrentHashMap<>();

    public String login(String username, String password) {
        if (!authProperty.getUsername().equals(username) ||
                !authProperty.getPassword().equals(password)) {
            throw new RuntimeException("用户名或密码错误");
        }
        String token = UUID.randomUUID().toString();
        tokenStore.put(token, true);
        return token;
    }

    public boolean validateToken(String token) {
        if (token == null) return false;
        return tokenStore.containsKey(token);
    }

    public void logout(String token) {
        tokenStore.remove(token);
    }
}
