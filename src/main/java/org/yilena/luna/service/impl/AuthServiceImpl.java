package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.AuthConstants;
import org.yilena.luna.constants.HttpConstants;
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
        // 校验账号密码，不匹配直接抛出鉴权异常。
        if (!authProperty.getUsername().equals(username) ||
                !authProperty.getPassword().equals(password)) {
            throw new AuthException("用户名或密码错误");
        }

        // 登录成功后签发带过期时间与 jti 的 JWT。
        String token = issueJwt(username);
        log.info("用户 {} 登录成功，签发 JWT", username);
        return token;
    }

    @Override
    public boolean validateToken(String token) {
        try {
            // 先做签名校验并解析 payload。
            JsonNode payload = parseAndVerify(token);
            if (payload == null) {
                return false;
            }

            // 校验 exp 过期时间。
            long now = Instant.now().getEpochSecond();
            long exp = payload.path("exp").asLong(0L);
            if (exp <= 0 || now >= exp) {
                log.warn("JWT 已过期或无效 exp");
                return false;
            }

            // 校验 jti 并判断是否被登出黑名单拦截。
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
            // 仅在签名与有效期通过时返回 jti。
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
    public String extractSubject(String token) {
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
            String sub = payload.path("sub").asText(null);
            return sub == null || sub.isBlank() ? null : sub;
        } catch (Exception e) {
            log.warn("extract subject failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void logout(String token) {
        // 将 jti 加入黑名单，实现令牌失效。
        String jti = extractJti(token);
        if (jti != null) {
            revokedJti.add(jti);
            log.info("JWT 已登出，jti={}", jti);
        }
    }

    private String issueJwt(String username) {
        try {
            // 生成签发时间、过期时间和唯一 jti。
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

            // 序列化 header/payload 并进行 Base64URL 编码。
            String headerB64 = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = headerB64 + "." + payloadB64;
            // 对签名输入做 HMAC-SHA256 签名。
            String signatureB64 = sign(signingInput);

            return signingInput + "." + signatureB64;
        } catch (Exception e) {
            throw new RuntimeException("签发 JWT 失败: " + e.getMessage(), e);
        }
    }

    private JsonNode parseAndVerify(String rawToken) throws Exception {
        // 兼容 "Bearer xxx" 格式并提取纯 token。
        String token = normalizeToken(rawToken);
        if (token == null || token.isBlank()) {
            return null;
        }

        // JWT 必须严格由 header.payload.signature 三段组成。
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        String headerB64 = parts[0];
        String payloadB64 = parts[1];
        String signatureB64 = parts[2];

        String signingInput = headerB64 + "." + payloadB64;
        String expectedSig = sign(signingInput);

        // 使用常量时间比较签名，降低时序攻击风险。
        byte[] actual = signatureB64.getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedSig.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(actual, expected)) {
            return null;
        }

        // 强校验算法标识，防止算法降级攻击。
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
        if (t.regionMatches(true, 0, HttpConstants.BEARER_PREFIX, 0, HttpConstants.BEARER_PREFIX.length())) {
            t = t.substring(HttpConstants.BEARER_PREFIX.length()).trim();
        }
        return t;
    }

    private String sign(String content) throws Exception {
        String secret = authProperty.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            // 未配置密钥时使用默认值并明确告警。
            secret = AuthConstants.DEFAULT_JWT_SECRET;
            log.warn("auth.jwt-secret 未配置，当前使用默认值，存在安全风险");
        }

        // 使用 HmacSHA256 生成签名并输出 Base64URL 文本。
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
