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
 * 认证服务实现类，负责登录鉴权、JWT 签发校验以及登出失效控制。
 */
public class AuthServiceImpl implements AuthService {

    /**
     * JWT 头部使用的签名算法标识。
     */
    private static final String JWT_ALG = "HS256";
    /**
     * JWT 头部使用的令牌类型标识。
     */
    private static final String JWT_TYP = "JWT";

    /**
     * 认证配置属性，提供账号密码和 JWT 配置。
     */
    private final AuthProperty authProperty;
    /**
     * JSON 处理器，用于构造和解析 JWT 头部与载荷。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 已登出令牌的 jti 黑名单，服务重启后会清空。
     */
    private final Set<String> revokedJti = ConcurrentHashMap.newKeySet();

    @Override
    /**
     * 校验账号密码并签发新的 JWT。
     */
    public String login(String username, String password) {
        /**
         * 先校验账号密码，不匹配时直接抛出鉴权异常，阻断非法登录。
         */
        if (!authProperty.getUsername().equals(username) ||
                !authProperty.getPassword().equals(password)) {
            throw new AuthException("用户名或密码错误");
        }

        /**
         * 登录成功后签发新的 JWT，供后续接口鉴权使用。
         */
        String token = issueJwt(username);
        log.info("用户 {} 登录成功，已签发 JWT", username);
        return token;
    }

    @Override
    /**
     * 校验令牌是否合法且未过期、未被主动注销。
     */
    public boolean validateToken(String token) {
        try {
            /**
             * 先完成签名校验并解析 payload，签名不通过时直接判定为无效令牌。
             */
            JsonNode payload = parseAndVerify(token);
            if (payload == null) {
                return false;
            }

            /**
             * 校验过期时间，避免继续接受已失效的令牌。
             */
            long now = Instant.now().getEpochSecond();
            long exp = payload.path("exp").asLong(0L);
            if (exp <= 0 || now >= exp) {
                log.warn("JWT 已过期或 exp 无效");
                return false;
            }

            /**
             * 校验 jti 是否存在且未被加入登出黑名单，确保登出后令牌立即失效。
             */
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
    /**
     * 在令牌合法的前提下提取 jti，供会话标识和登出失效使用。
     */
    public String extractJti(String token) {
        try {
            /**
             * 只有在签名、有效期和黑名单校验通过后，才返回 jti 给上游继续使用。
             */
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
    /**
     * 在令牌合法时提取 subject，供识别当前登录用户使用。
     */
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
    /**
     * 将令牌对应的 jti 加入黑名单，实现服务内即时登出。
     */
    public void logout(String token) {
        String jti = extractJti(token);
        if (jti != null) {
            revokedJti.add(jti);
            log.info("JWT 已登出，jti={}", jti);
        }
    }

    /**
     * 生成包含有效期和唯一 jti 的 JWT。
     */
    private String issueJwt(String username) {
        try {
            /**
             * 先生成签发时间、过期时间和唯一 jti，保证令牌可追踪且具备时效性。
             */
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

            /**
             * 对头部和载荷做 Base64URL 编码后拼接签名输入，再生成最终签名段。
             */
            String headerB64 = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = headerB64 + "." + payloadB64;
            String signatureB64 = sign(signingInput);

            return signingInput + "." + signatureB64;
        } catch (Exception e) {
            throw new RuntimeException("签发 JWT 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析并校验 JWT 的签名、结构和算法声明。
     */
    private JsonNode parseAndVerify(String rawToken) throws Exception {
        /**
         * 先兼容 Bearer 前缀并提取纯 token 文本。
         */
        String token = normalizeToken(rawToken);
        if (token == null || token.isBlank()) {
            return null;
        }

        /**
         * JWT 必须由 header.payload.signature 三段组成，结构异常时直接拒绝。
         */
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        String headerB64 = parts[0];
        String payloadB64 = parts[1];
        String signatureB64 = parts[2];

        String signingInput = headerB64 + "." + payloadB64;
        String expectedSig = sign(signingInput);

        /**
         * 使用常量时间比较签名，降低时序攻击风险。
         */
        byte[] actual = signatureB64.getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedSig.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(actual, expected)) {
            return null;
        }

        /**
         * 强校验算法标识，防止算法降级或篡改头部。
         */
        String headerJson = new String(base64UrlDecode(headerB64), StandardCharsets.UTF_8);
        JsonNode header = objectMapper.readTree(headerJson);
        String alg = header.path("alg").asText("");
        if (!JWT_ALG.equalsIgnoreCase(alg)) {
            return null;
        }

        String payloadJson = new String(base64UrlDecode(payloadB64), StandardCharsets.UTF_8);
        return objectMapper.readTree(payloadJson);
    }

    /**
     * 兼容带 Bearer 前缀的令牌格式，统一提取纯 token 内容。
     */
    private String normalizeToken(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.regionMatches(true, 0, HttpConstants.BEARER_PREFIX, 0, HttpConstants.BEARER_PREFIX.length())) {
            t = t.substring(HttpConstants.BEARER_PREFIX.length()).trim();
        }
        return t;
    }

    /**
     * 使用 HmacSHA256 对指定内容生成 Base64URL 签名。
     */
    private String sign(String content) throws Exception {
        String secret = authProperty.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            /**
             * 未配置密钥时回退到默认值，同时明确记录风险，避免静默使用弱配置。
             */
            secret = AuthConstants.DEFAULT_JWT_SECRET;
            log.warn("auth.jwt-secret 未配置，当前使用默认值，存在安全风险");
        }

        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] sig = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(sig);
    }

    /**
     * 执行不带填充的 Base64URL 编码。
     */
    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 执行 Base64URL 解码。
     */
    private byte[] base64UrlDecode(String text) {
        return Base64.getUrlDecoder().decode(text);
    }
}
