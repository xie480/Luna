package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.exception.impl.AuthException;
import org.yilena.luna.properties.AuthProperty;
import org.yilena.luna.service.AuthService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * AuthServiceImpl ??
 */
public class AuthServiceImpl implements AuthService {

    private static final String JWT_ALG = "HS256";
    private static final String JWT_TYP = "JWT";

    private final AuthProperty authProperty;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 已登出 jti 黑名单（服务重启后清空）
     */
    private final Set<String> revokedJti = ConcurrentHashMap.newKeySet();

    @Override
    public String login(String username, String password) {
        if (!authProperty.getUsername().equals(username) ||
                !authProperty.getPassword().equals(password)) {
            throw new AuthException("用户名或密码错误");
        }

        String token = issueJwt(username);
        log.info("用户 {} 登录成功，签发 JWT", username);
        return token;
    }

    @Override
    public boolean validateToken(String token) {
        try {
            JsonNode payload = parseAndVerify(token);
            if (payload == null) {
                return false;
            }

            long now = Instant.now().getEpochSecond();
            long exp = payload.path("exp").asLong(0L);
            if (exp <= 0 || now >= exp) {
                log.warn("JWT 已过期或无效 exp");
                return false;
            }

            String jti = payload.path("jti").asText(null);
            if (jti == null || jti.isBlank()) {
                log.warn("JWT 缺少 jti");
                return false;
            }

            if (revokedJti.contains(jti)) {
                log.warn("JWT 已登出失效，jti={}", jti);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("JWT 校验失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String extractJti(String token) {
        try {
            JsonNode payload = parseAndVerify(token);
            if (payload == null) {
                return null;
            }

            long now = Instant.now().getEpochSecond();
            long exp = payload.path("exp").asLong(0L);
            if (exp <= 0 || now >= exp) {
                return null;
            }

            String jti = payload.path("jti").asText(null);
            if (jti == null || jti.isBlank() || revokedJti.contains(jti)) {
                return null;
            }
            return jti;
        } catch (Exception e) {
            log.warn("提取 jti 失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void logout(String token) {
        String jti = extractJti(token);
        if (jti != null) {
            revokedJti.add(jti);
            log.info("JWT 已登出，jti={}", jti);
        }
    }

    private String issueJwt(String username) {
        try {
            long now = Instant.now().getEpochSecond();
            long exp = now + Math.max(60L, authProperty.getJwtExpireSeconds());
            String jti = UUID.randomUUID().toString().replace("-", "");

            String headerJson = objectMapper.writeValueAsString(Map.of(
                    "alg", JWT_ALG,
                    "typ", JWT_TYP
            ));
            String payloadJson = objectMapper.writeValueAsString(Map.of(
                    "sub", username,
                    "jti", jti,
                    "iat", now,
                    "exp", exp
            ));

            String headerB64 = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = headerB64 + "." + payloadB64;
            String signatureB64 = sign(signingInput);

            return signingInput + "." + signatureB64;
        } catch (Exception e) {
            throw new RuntimeException("签发 JWT 失败: " + e.getMessage(), e);
        }
    }

    private JsonNode parseAndVerify(String rawToken) throws Exception {
        String token = normalizeToken(rawToken);
        if (token == null || token.isBlank()) {
            return null;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        String headerB64 = parts[0];
        String payloadB64 = parts[1];
        String signatureB64 = parts[2];

        String signingInput = headerB64 + "." + payloadB64;
        String expectedSig = sign(signingInput);

        byte[] actual = signatureB64.getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedSig.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(actual, expected)) {
            return null;
        }

        String headerJson = new String(base64UrlDecode(headerB64), StandardCharsets.UTF_8);
        JsonNode header = objectMapper.readTree(headerJson);
        String alg = header.path("alg").asText("");
        if (!JWT_ALG.equalsIgnoreCase(alg)) {
            return null;
        }

        String payloadJson = new String(base64UrlDecode(payloadB64), StandardCharsets.UTF_8);
        return objectMapper.readTree(payloadJson);
    }

    private String normalizeToken(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        return t;
    }

    private String sign(String content) throws Exception {
        String secret = authProperty.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            secret = "change-me-in-production";
            log.warn("auth.jwt-secret 未配置，当前使用默认值，存在安全风险");
        }

        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] sig = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(sig);
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] base64UrlDecode(String text) {
        return Base64.getUrlDecoder().decode(text);
    }
}
