package org.yilena.luna.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ToolDecisionInputSignatureUtil {

    private ToolDecisionInputSignatureUtil() {
    }

    public static String sign(String sessionId, String decisionInput, String assembledDecisionContext) {
        String payload = "v1|session=" + nullSafe(sessionId)
                + "|decision=" + nullSafe(decisionInput)
                + "|assembled_sha=" + sha256Hex(nullSafe(assembledDecisionContext));
        return sha256Hex(payload);
    }

    public static boolean verify(String signature, String sessionId, String decisionInput, String assembledDecisionContext) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = sign(sessionId, decisionInput, assembledDecisionContext);
        return expected.equals(signature);
    }

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

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
