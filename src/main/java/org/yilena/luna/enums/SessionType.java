package org.yilena.luna.enums;

import java.util.Locale;

/**
 * 会话类型枚举，用于区分任务型、陪伴型和混合型会话。
 */
public enum SessionType {
    /**
     * 任务导向会话。
     */
    TASK,
    /**
     * 陪伴导向会话。
     */
    COMPANION,
    /**
     * 同时包含任务和陪伴特征的混合会话。
     */
    HYBRID;

    /**
     * 根据原始字符串解析会话类型，无法识别时回退为 HYBRID。
     */
    public static SessionType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return HYBRID;
        }
        try {
            return SessionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return HYBRID;
        }
    }
}
