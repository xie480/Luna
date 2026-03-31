package org.yilena.luna.enums;

import java.util.Locale;

public enum SessionType {
    TASK,
    COMPANION,
    HYBRID;

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


