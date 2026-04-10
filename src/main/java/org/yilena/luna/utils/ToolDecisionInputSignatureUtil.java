package org.yilena.luna.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 该工具类用于为工具决策输入生成和校验签名，防止上下文或决策输入在链路中被意外篡改。
 */
public final class ToolDecisionInputSignatureUtil {

    private ToolDecisionInputSignatureUtil() {
    }

    /**
     * 根据会话、决策输入和组装后的上下文生成稳定签名，用于后续一致性校验。
     */
    public static String sign(String sessionId, String decisionInput, String assembledDecisionContext) {
        String payload = "v1|session=" + nullSafe(sessionId)
                + "|decision=" + nullSafe(decisionInput)
                + "|assembled_sha=" + sha256Hex(nullSafe(assembledDecisionContext));
        return sha256Hex(payload);
    }

    /**
     * 校验传入签名与当前输入是否一致，确保工具决策基于同一份上下文完成。
     */
    public static boolean verify(String signature, String sessionId, String decisionInput, String assembledDecisionContext) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = sign(sessionId, decisionInput, assembledDecisionContext);
        return expected.equals(signature);
    }

    /**
     * 对文本执行 SHA-256 哈希并输出十六进制字符串。
     */
    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception ignore) {
            return "";
        }
    }

    /**
     * 将空值统一转为空字符串，避免签名拼接时出现 null 文本。
     */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
