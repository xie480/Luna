package org.yilena.luna.service;

public interface AuthService {
    String login(String username, String password);

    void logout(String token);

    boolean validateToken(String token);

    String extractJti(String token);

    String extractSubject(String token);
}
